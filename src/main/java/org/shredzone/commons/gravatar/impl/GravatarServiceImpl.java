/*
 * Shredzone Commons
 *
 * Copyright (C) 2012 Richard "Shred" Körber
 *   http://commons.shredzone.org
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.shredzone.commons.gravatar.impl;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.shredzone.commons.gravatar.GravatarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link GravatarService}.
 *
 * @author Richard "Shred" Körber
 */
@Component("gravatarService")
public class GravatarServiceImpl implements GravatarService {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private static final int MAX_GRAVATAR_SIZE = 256 * 1024;        // 256 KiB per image
    private static final int MAX_CACHE_ENTRIES = 500;               // 500 entries
    private static final int MAX_REQUESTS_COUNT = 1000;             // 1000 requests
    private static final long MAX_REQUESTS_RECOVERY = 60 * 1000L;   // per minute
    private static final int TIMEOUT = 10000;                       // 10 seconds
    private static final long CACHE_CLEANUP = 60 * 60 * 1000L;      // every hour

    @Value("${gravatar.cache.path}")
    private String cachePath;

    @Value("${gravatar.cache.alive}")
    private int aliveSeconds;

    @Value("${gravatar.cache.url}")
    private String gravatarUrl;

    private int requestCounter = 0;
    private long lastRequest = System.currentTimeMillis();

    @Override
    public String computeHash(String mail) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.reset();
            md5.update(mail.trim().toLowerCase().getBytes("UTF-8"));

            byte[] digest = md5.digest();

            return IntStream.range(0, digest.length)
                    .mapToObj(ix -> String.format("%02x", digest[ix] & 0xFF))
                    .collect(joining());
        } catch (NoSuchAlgorithmException|UnsupportedEncodingException ex) {
            // should never happen since we use standard stuff
            throw new InternalError(ex.getMessage());
        }
    }

    @Override
    public File fetchGravatar(String hash) throws IOException {
        synchronized (this) {
            File file = new File(cachePath, hash);

            if (file.exists() && file.isFile() && !isExpired(file)) {
                return file;
            }

            URL url = new URL(gravatarUrl.replace("{}", hash));
            fetchGravatar(url, file);

            return file;
        }
    }

    /**
     * Checks if the file cache lifetime is expired.
     *
     * @param file
     *            {@link File} to check
     * @return {@code true} if the file is older than cache lifetime
     */
    private boolean isExpired(File file) {
        long fileTs = file.lastModified();
        long expiryTs = System.currentTimeMillis() - (aliveSeconds * 1000L);

        return (fileTs < expiryTs);
    }

    /**
     * Limits the number of requests to the upstream server. After the limit was reached,
     * no further requests are permitted for the given recovery time. This way, attacks
     * to the cache servlet have no impact on the upstream server.
     *
     * @throws IOException
     *             when the limit was reached
     */
    private void limitUpstreamRequests() throws IOException {
        long recoveryTs = System.currentTimeMillis() - MAX_REQUESTS_RECOVERY;
        if (lastRequest < recoveryTs) {
            requestCounter = 0;
        }

        requestCounter++;

        if (requestCounter > MAX_REQUESTS_COUNT && lastRequest >= recoveryTs) {
            log.warn("More than {} requests were made to Gravatar server, recovering!", MAX_REQUESTS_COUNT);
            throw new IOException("Request limit reached");
        }

        lastRequest = System.currentTimeMillis();
    }

    /**
     * Cleans up the gravatar cache. Oldest entries are deleted until cache size is
     * valid again.
     */
    @Scheduled(fixedDelay = CACHE_CLEANUP)
    public void cacheCleanup() {
        Arrays.stream(new File(cachePath).listFiles())
                .filter(file -> file.isFile() && !file.isHidden())
                .sorted(comparing(File::lastModified).reversed()) // younger files first
                .skip(MAX_CACHE_ENTRIES)
                .forEach(this::delete);
    }

    /**
     * Deletes a file, logs a warning if it could not be deleted.
     */
    private void delete(File file) {
        if (!file.delete()) {
            log.warn("Could not delete expired Gravatar cache object: " + file.getPath());
        }
    }

    /**
     * Fetches a Gravatar icon from the server and stores it in the given {@link File}.
     *
     * @param url
     *            Gravatar URL to fetch
     * @param file
     *            {@link File} to store the icon to
     */
    private void fetchGravatar(URL url, File file) throws IOException {
        limitUpstreamRequests();

        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);

        if (file.exists()) {
            conn.setIfModifiedSince(file.lastModified());
        }

        conn.connect();

        long lastModified = conn.getLastModified();
        if (lastModified > 0L && lastModified <= file.lastModified()) {
            // Cache file exists and is unchanged
            if (log.isDebugEnabled()) {
                log.debug("Cached Gravatar is still good: {}", url);
            }

            file.setLastModified(System.currentTimeMillis()); // touch
            return;
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int len;

            while ((len = in.read(buffer)) >= 0) {
                out.write(buffer, 0, len);
                total += len;
                if (total > MAX_GRAVATAR_SIZE) {
                    log.warn("Gravatar exceeded maximum size: {}", url);
                    break;
                }
            }

            out.flush();

            if (log.isDebugEnabled()) {
                log.debug("Downloaded Gravatar: {}", url);
            }
        }
    }

}

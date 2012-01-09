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
import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;

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

            StringBuilder digest = new StringBuilder();
            for (byte b : md5.digest()) {
                digest.append(String.format("%02x", b & 0xFF));
            }

            return digest.toString();
        } catch (NoSuchAlgorithmException ex) {
            // should never happen since MD5 is a standard digester
            throw new InternalError("no md5 hashing");
        } catch (UnsupportedEncodingException ex) {
            // should never happen since UTF-8 is a standard encoding
            throw new InternalError("no utf-8 encoding");
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
        // Read all files sorted by their modification date, oldest first
        SortedMap<Long, File> fileMap = new TreeMap<Long, File>();
        for (File file : new File(cachePath).listFiles()) {
            if (file.isFile() && !file.isHidden()) {
                fileMap.put(file.lastModified(), file);
            }
        }

        // Delete oldest entries until cache size is good again
        while (fileMap.size() > MAX_CACHE_ENTRIES) {
            Iterator<File> it = fileMap.values().iterator();
            File file = it.next();
            if (!file.delete()) {
                log.warn("Could not delete expired Gravatar cache object: " + file.getPath());
            }
            it.remove();
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

        InputStream in = null;
        OutputStream out = null;
        byte[] buffer = new byte[8192];

        try {
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

            in = conn.getInputStream();
            out = new FileOutputStream(file);

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

        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        }
    }

}

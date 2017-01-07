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

package org.shredzone.commons.gravatar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.servlet.FrameworkServlet;

/**
 * A servlet that proxies requests to the Gravatar server, and caches the image results.
 * <p>
 * By using this servlet instead of immediate requests to Gravatar, page loading speed is
 * kept high even if the Gravatar servers are currently under high load or unreachable.
 * Additionally, privacy is kept because the visitor's IP and browser headers are kept
 * hidden from the Gravatar servers.
 *
 * @author Richard "Shred" Körber
 */
public class GravatarCacheServlet extends FrameworkServlet {
    private static final long serialVersionUID = 5962372398781412921L;

    private static final Logger LOG = LoggerFactory.getLogger(GravatarCacheServlet.class);
    private static final Pattern HASH_PATTERN = Pattern.compile("/([0-9a-f]{32})");

    @Override
    protected void doService(HttpServletRequest req, HttpServletResponse resp)
    throws Exception {
        try {
            Matcher m = HASH_PATTERN.matcher(req.getPathInfo());
            if (! m.matches()) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            String hash = m.group(1);

            GravatarService gs = getWebApplicationContext().getBean("gravatarService", GravatarService.class);
            File gravatarFile = gs.fetchGravatar(hash);

            long modifiedSinceTs = getIfModifiedSince(req);
            if (modifiedSinceTs >= 0
                    && (modifiedSinceTs / 1000L) == (gravatarFile.lastModified() / 1000L)) {
                // The image has not been modified since last request
                resp.sendError(HttpServletResponse.SC_NOT_MODIFIED);
                return;
            }

            long size = gravatarFile.length();
            if (size > 0 && size <= Integer.MAX_VALUE) {
                // Cast to int is safe
                resp.setContentLength((int) size);
            }

            resp.setContentType("image/png");
            resp.setDateHeader("Date", System.currentTimeMillis());
            resp.setDateHeader("Last-Modified", gravatarFile.lastModified());

            try (InputStream in = new FileInputStream(gravatarFile)) {
                FileCopyUtils.copy(in, resp.getOutputStream());
            }
        } catch (IOException | BeansException ex) {
            LOG.error("Failed to send Gravatar icon", ex);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage()); //NOSONAR
        }
    }

    /**
     * Reads the If-Modified-Since header.
     *
     * @return date returned in the If-Modified-Since header, or -1 if not set or not
     *         readable
     */
    private long getIfModifiedSince(HttpServletRequest req) {
        try {
            return req.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException ex) {
            // As stated in RFC2616 Sec. 14.25, an invalid date will just be ignored.
            LOG.debug("Ignored a bad If-Modified-Since header", ex);
            return -1;
        }
    }

}

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
import java.io.IOException;

/**
 * A service for accessing Gravatar avatars.
 *
 * @author Richard "Shred" Körber
 */
public interface GravatarService {

    /**
     * Computes the Gravatar hash for a mail address. Actually, this is the md5 sum of the
     * mail address.
     *
     * @param mail
     *            Mail address to hash
     * @return Gravatar hash
     */
    String computeHash(String mail);

    /**
     * Fetch a File of the Gravatar image for the given hash. If there is no such file,
     * the image is downloaded from the Gravatar server, and stored in the file system.
     * <p>
     * The files are cached for a certain time.
     *
     * @param hash
     *            Gravatar image hash
     * @return File containing the image
     */
    File fetchGravatar(String hash) throws IOException;

}

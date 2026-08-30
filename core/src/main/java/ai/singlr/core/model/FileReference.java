/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import ai.singlr.core.common.Strings;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** A provider-accessible media file referenced by URI instead of embedded in a request. */
public record FileReference(String uri, String mimeType) {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("gs", "http", "https");
  private static final Pattern MIME_TYPE =
      Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");

  public FileReference {
    if (Strings.isBlank(uri)) {
      throw new IllegalArgumentException("uri must not be null or blank");
    }
    var parsed = parseUri(uri);
    if (!parsed.isAbsolute()
        || !ALLOWED_SCHEMES.contains(parsed.getScheme().toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("uri must use the gs, http, or https scheme");
    }
    if (parsed.getHost() == null) {
      throw new IllegalArgumentException("uri must include a host or storage bucket");
    }
    if (parsed.getRawUserInfo() != null) {
      throw new IllegalArgumentException("uri must not contain user credentials");
    }
    if (Strings.isBlank(mimeType) || !MIME_TYPE.matcher(mimeType).matches()) {
      throw new IllegalArgumentException("mimeType must be a valid media type");
    }
  }

  public static FileReference of(String uri, String mimeType) {
    return new FileReference(uri, mimeType);
  }

  private static URI parseUri(String uri) {
    try {
      return URI.create(uri);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("uri must be a valid absolute URI", e);
    }
  }
}

/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import com.standardapplied.helios.core.common.Strings;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A provider-accessible media file referenced by URI instead of embedded in a request.
 *
 * @param uri where the provider fetches the file: an absolute {@code gs}, {@code http} or {@code
 *     https} URI with a host (for {@code gs}, the storage bucket) and no user credentials
 * @param mimeType the file's media type, {@code type/subtype}, such as {@code application/pdf}
 */
public record FileReference(String uri, String mimeType) {

  private static final Set<String> ALLOWED_SCHEMES = Set.of("gs", "http", "https");
  private static final Pattern MIME_TYPE =
      Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");

  /**
   * Canonical constructor.
   *
   * @throws IllegalArgumentException if {@code uri} is null, blank, not a valid absolute URI, of
   *     another scheme, without a host or carrying user info, or if {@code mimeType} is null, blank
   *     or not a {@code type/subtype} media type
   */
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

  /**
   * Creates a file reference, validated as the canonical constructor validates it.
   *
   * @param uri where the provider fetches the file; an absolute {@code gs}, {@code http} or {@code
   *     https} URI with a host and no user credentials
   * @param mimeType the file's media type, {@code type/subtype}
   * @return the file reference
   * @throws IllegalArgumentException if {@code uri} or {@code mimeType} is not valid
   */
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

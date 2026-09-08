/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable stamp of a file's contents at a moment in time. The Phase 3 edit tools compare the
 * fingerprint captured at read against the current fingerprint to detect concurrent mutations
 * ("stale file") before applying an edit.
 *
 * <p>The SHA-256 covers content; mtime + size are kept alongside to catch races where two writes
 * produce the same bytes but the file moved.
 *
 * @param mtime the file's last-modified instant; non-null
 * @param size the file's size in bytes; non-negative
 * @param sha256 lowercase hex of the SHA-256 of the file's bytes; 64 characters
 */
public record FileFingerprint(Instant mtime, long size, String sha256) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code mtime} or {@code sha256} is null
   * @throws IllegalArgumentException if {@code size} is negative or {@code sha256} is not a 64-char
   *     lowercase hex string
   */
  public FileFingerprint {
    Objects.requireNonNull(mtime, "mtime must not be null");
    if (size < 0) {
      throw new IllegalArgumentException("size must be non-negative, got " + size);
    }
    Objects.requireNonNull(sha256, "sha256 must not be null");
    if (sha256.length() != 64) {
      throw new IllegalArgumentException(
          "sha256 must be 64 hex chars, got " + sha256.length() + ": " + sha256);
    }
    for (var c : sha256.toCharArray()) {
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        throw new IllegalArgumentException("sha256 must be lowercase hex: " + sha256);
      }
    }
  }

  private static final int READ_BUFFER_BYTES = 64 * 1024;

  /**
   * Compute a fingerprint for the file at {@code path} by streaming its bytes through a SHA-256
   * digest. Memory footprint is constant ({@value #READ_BUFFER_BYTES}-byte buffer) regardless of
   * file size, so multi-gigabyte files do not pressure the session heap.
   *
   * @param path the file; must exist and be a regular file. Symlinks are never followed
   * @return a fresh fingerprint
   * @throws NullPointerException if {@code path} is null
   * @throws IOException if reading fails
   */
  public static FileFingerprint of(Path path) throws IOException {
    Objects.requireNonNull(path, "path must not be null");
    var digest = newSha256();
    long size = 0L;
    var buffer = new byte[READ_BUFFER_BYTES];
    try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        var digestStream = new DigestInputStream(in, digest)) {
      int read;
      while ((read = digestStream.read(buffer)) != -1) {
        size += read;
      }
    }
    var mtime = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
    return new FileFingerprint(mtime, size, HexFormat.of().formatHex(digest.digest()));
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 must be available on every JVM", e);
    }
  }
}

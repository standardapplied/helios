/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe registry of secret values that must never appear in model-visible output.
 *
 * <p>Secrets registered here are scrubbed from any text passed through a {@link Redactor} obtained
 * via {@link #redactor()}. The redactor is a point-in-time snapshot; secrets registered after the
 * snapshot is taken require a fresh redactor to be observed.
 *
 * <p>Validation rules applied at registration:
 *
 * <ul>
 *   <li>Names must be non-blank and unique. Re-registering the same name overwrites the value.
 *   <li>Values must be at least {@value #MIN_SECRET_LENGTH} characters. Shorter values are
 *       brute-forceable and would shred surrounding output if redacted blindly.
 *   <li>Values must be <em>JSON-safe printable ASCII</em>: every byte in {@code [0x20, 0x7E]}
 *       except {@code 0x22} ({@code "}) and {@code 0x5C} ({@code \}). Control characters ({@code
 *       0x00–0x1F}, {@code 0x7F}) and non-ASCII characters are refused. The Redactor is a
 *       byte-level scanner, so a registered secret whose bytes change shape during JSON
 *       serialisation (Jackson escapes {@code "} as {@code \"}, {@code \n} as {@code \\n}, etc.)
 *       would survive verbatim in any downstream JSON column the redactor scrubs post-serialise.
 *       Refusing JSON-unsafe bytes at registration makes byte-level redaction provably safe in both
 *       pre-serialise and post-serialise call sites.
 * </ul>
 */
public final class SecretRegistry {

  /** Minimum allowed secret length in characters. */
  public static final int MIN_SECRET_LENGTH = 8;

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final LinkedHashMap<String, byte[]> secretsByName = new LinkedHashMap<>();

  public SecretRegistry() {}

  /**
   * Register a secret value under a logical name. Re-registering the same name overwrites the
   * previously stored value.
   *
   * @param name the logical name; appears in the redaction marker as {@code <redacted:NAME>}
   * @param value the secret value
   * @throws IllegalArgumentException if name is blank, value is null, value is shorter than {@link
   *     #MIN_SECRET_LENGTH}, or value contains any character outside JSON-safe printable ASCII
   *     ({@code [0x20, 0x7E]} excluding {@code "} and {@code \})
   */
  public void register(String name, String value) {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Secret name must not be blank");
    }
    if (value == null) {
      throw new IllegalArgumentException("Secret value must not be null");
    }
    if (value.length() < MIN_SECRET_LENGTH) {
      throw new IllegalArgumentException(
          "Secret '%s' must be at least %d characters; was %d"
              .formatted(name, MIN_SECRET_LENGTH, value.length()));
    }
    for (var i = 0; i < value.length(); i++) {
      var c = value.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        throw new IllegalArgumentException(
            ("Secret '%s' contains control character 0x%02X at index %d; only printable ASCII"
                    + " ([0x20, 0x7E] excluding \" and \\) is permitted so byte-level redaction"
                    + " stays safe across JSON serialisation")
                .formatted(name, (int) c, i));
      }
      if (c > 0x7E) {
        throw new IllegalArgumentException(
            "Secret '%s' must be pure ASCII; character at index %d is non-ASCII"
                .formatted(name, i));
      }
      if (c == '"' || c == '\\') {
        throw new IllegalArgumentException(
            ("Secret '%s' contains JSON-special character '%c' at index %d; Jackson would escape"
                    + " it during serialisation, leaving the raw bytes invisible to the"
                    + " byte-level Redactor and the secret leakable through any JSON column the"
                    + " redactor scrubs post-serialise")
                .formatted(name, c, i));
      }
    }
    var bytes = value.getBytes(StandardCharsets.US_ASCII);
    lock.writeLock().lock();
    try {
      secretsByName.put(name, bytes);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Returns true if {@code candidate} contains, as a contiguous byte sequence, the value of any
   * registered secret. Used to refuse argv that would otherwise carry a secret in clear into the
   * process listing.
   */
  public boolean leaks(String candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return false;
    }
    var bytes = candidate.getBytes(StandardCharsets.UTF_8);
    lock.readLock().lock();
    try {
      for (var stored : secretsByName.values()) {
        if (containsBytes(bytes, stored)) {
          return true;
        }
      }
      return false;
    } finally {
      lock.readLock().unlock();
    }
  }

  private static boolean containsBytes(byte[] haystack, byte[] needle) {
    if (needle.length > haystack.length) {
      return false;
    }
    outer:
    for (var i = 0; i <= haystack.length - needle.length; i++) {
      for (var j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Returns an immutable snapshot of registered secrets as a {@link Redactor}. Subsequent mutations
   * to this registry do not affect the returned redactor.
   */
  public Redactor redactor() {
    lock.readLock().lock();
    try {
      return Redactor.of(secretsByName);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Number of currently registered secrets. */
  public int size() {
    lock.readLock().lock();
    try {
      return secretsByName.size();
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Returns the registered secret names. */
  public Set<String> names() {
    lock.readLock().lock();
    try {
      return Set.copyOf(secretsByName.keySet());
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Snapshot of (name, bytes) pairs. Defensive copies returned. */
  Map<String, byte[]> snapshot() {
    lock.readLock().lock();
    try {
      var copy = new LinkedHashMap<String, byte[]>(secretsByName.size());
      for (var entry : secretsByName.entrySet()) {
        copy.put(entry.getKey(), entry.getValue().clone());
      }
      return copy;
    } finally {
      lock.readLock().unlock();
    }
  }
}

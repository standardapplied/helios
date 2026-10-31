/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Thread-safe registry of secret values that must never appear in model-visible output.
 *
 * <p>Secrets registered here are scrubbed from any text passed through a {@link Redactor} obtained
 * via {@link #redactor()}. The redactor is an immutable snapshot, cached until the next {@link
 * #register}; secrets registered after it was obtained require a fresh call to be observed.
 *
 * <p>Validation rules applied at registration:
 *
 * <ul>
 *   <li>Names must be non-blank and unique. Re-registering the same name overwrites the value.
 *   <li>Values must be at least {@value #MIN_SECRET_LENGTH} characters. Shorter values are
 *       brute-forceable and would shred surrounding output if redacted blindly.
 *   <li>Values must be <em>JSON-safe printable ASCII</em>: every character in {@code [0x20, 0x7E]}
 *       except {@code 0x22} ({@code "}) and {@code 0x5C} ({@code \}). Control characters ({@code
 *       0x00–0x1F}, {@code 0x7F}) and non-ASCII characters are refused. A registered value must
 *       keep its exact text through JSON serialisation: one that changes shape (Jackson escapes
 *       {@code "} as {@code \"}, {@code \n} as {@code \\n}, etc.) would survive in any downstream
 *       JSON column the redactor scrubs post-serialise.
 *   <li>Values must not start or end with a space. A key or text that is stripped, such as {@code
 *       Execute}'s permission key, would cut that space and no longer hold the whole secret.
 * </ul>
 */
public final class SecretRegistry {

  /** Minimum allowed secret length in characters. */
  public static final int MIN_SECRET_LENGTH = 8;

  private final Object lock = new Object();
  private volatile Snapshot snapshot = new Snapshot(Map.of(), Redactor.of(Map.of()));

  public SecretRegistry() {}

  /**
   * Register a secret value under a logical name. Re-registering the same name overwrites the
   * previously stored value.
   *
   * @param name the logical name; appears in the redaction marker as {@code <redacted:NAME>}
   * @param value the secret value
   * @throws IllegalArgumentException if name is blank, value is null, value is shorter than {@link
   *     #MIN_SECRET_LENGTH}, or value contains any character outside JSON-safe printable ASCII
   *     ({@code [0x20, 0x7E]} excluding {@code "} and {@code \}), or value starts or ends with a
   *     space
   */
  public void register(String name, String value) {
    validate(name, value);
    synchronized (lock) {
      var secrets = new LinkedHashMap<>(snapshot.secrets());
      secrets.put(name, value);
      snapshot = new Snapshot(Collections.unmodifiableMap(secrets), Redactor.of(secrets));
    }
  }

  private static void validate(String name, String value) {
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
      validateCharacter(name, value.charAt(i), i);
    }
    if (value.startsWith(" ") || value.endsWith(" ")) {
      throw new IllegalArgumentException(
          "Secret '%s' must not start or end with a space".formatted(name));
    }
  }

  private static void validateCharacter(String name, char c, int index) {
    if (c < 0x20 || c == 0x7F) {
      throw new IllegalArgumentException(
          ("Secret '%s' contains control character 0x%02X at index %d; only printable ASCII"
                  + " ([0x20, 0x7E] excluding \" and \\) is permitted so a registered value"
                  + " keeps its exact text through JSON serialisation")
              .formatted(name, (int) c, index));
    }
    if (c > 0x7E) {
      throw new IllegalArgumentException(
          "Secret '%s' must be pure ASCII; character at index %d is non-ASCII"
              .formatted(name, index));
    }
    if (c == '"' || c == '\\') {
      throw new IllegalArgumentException(
          ("Secret '%s' contains JSON-special character '%c' at index %d; Jackson would escape"
                  + " it during serialisation, leaving the raw text invisible to the"
                  + " Redactor and the secret leakable through any JSON column the"
                  + " redactor scrubs post-serialise")
              .formatted(name, c, index));
    }
  }

  /**
   * Returns true if {@code candidate} contains the value of any registered secret. Used to refuse
   * argv that would otherwise carry a secret in clear into the process listing.
   */
  public boolean leaks(String candidate) {
    return candidate != null && snapshot.secrets().values().stream().anyMatch(candidate::contains);
  }

  /**
   * Returns the {@link Redactor} of the registered secrets: the same instance until the next {@link
   * #register}, which does not affect it.
   */
  public Redactor redactor() {
    return snapshot.redactor();
  }

  /** Number of currently registered secrets. */
  public int size() {
    return snapshot.secrets().size();
  }

  /** Returns the registered secret names. */
  public Set<String> names() {
    return Set.copyOf(snapshot.secrets().keySet());
  }

  private record Snapshot(Map<String, String> secrets, Redactor redactor) {}
}

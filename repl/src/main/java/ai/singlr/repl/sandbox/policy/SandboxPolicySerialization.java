/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Argv-safe serialisation of {@link SandboxPolicy} for transport between the {@code JvmSandbox}
 * host launch path and the {@code JvmSandboxBootstrap} subprocess entry point.
 *
 * <p>The wire format is a pipe-separated list of {@code key=value} fields, base64-encoded so the
 * caller can drop it into a {@code --sandbox-policy=<...>} argv flag without worrying about shell
 * quoting. List values are comma-joined. Class and package names cannot contain {@code |}, {@code
 * ,}, or {@code =}, so no escaping is required for any valid policy.
 *
 * <p>The format is deliberately minimal — no JSON dependency in the bootstrap startup path, no
 * versioning preamble. The serialiser and deserialiser are co-located here so the format stays
 * symmetric; any breaking change must update both sides in lockstep.
 */
public final class SandboxPolicySerialization {

  static final String FIELD_DENIED_CLASSES = "deniedClasses";
  static final String FIELD_DENIED_PACKAGES = "deniedPackages";
  static final String FIELD_DENY_REFLECTION = "denyReflection";
  static final String FIELD_DENY_NATIVE = "denyNativeAccess";
  static final String FIELD_DENY_DYN_CLASS_DEF = "denyDynamicClassDefinition";
  static final String FIELD_ON_VIOLATION = "onViolation";

  private SandboxPolicySerialization() {}

  /**
   * Encode {@code policy} as an argv-safe base64 string. The caller is responsible for passing this
   * via {@code --sandbox-policy=<encoded>} on the subprocess command line.
   */
  public static String encode(SandboxPolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("policy must not be null");
    }
    var sb = new StringBuilder();
    appendField(sb, FIELD_DENIED_CLASSES, joinSorted(policy.deniedClasses()));
    appendField(sb, FIELD_DENIED_PACKAGES, joinSorted(policy.deniedPackages()));
    appendField(sb, FIELD_DENY_REFLECTION, Boolean.toString(policy.denyReflection()));
    appendField(sb, FIELD_DENY_NATIVE, Boolean.toString(policy.denyNativeAccess()));
    appendField(
        sb, FIELD_DENY_DYN_CLASS_DEF, Boolean.toString(policy.denyDynamicClassDefinition()));
    appendField(sb, FIELD_ON_VIOLATION, policy.onViolation().name());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decode an argv-safe encoded policy back into a {@link SandboxPolicy}. Unknown fields are
   * ignored (forward compatibility); missing fields fall back to the permissive default for that
   * field so an older bootstrap reading a newer policy never crashes — it just under-enforces, and
   * the older bootstrap is the deployer's choice to ship.
   *
   * @throws IllegalArgumentException if the input is not valid base64 or the decoded form contains
   *     malformed field syntax
   */
  public static SandboxPolicy decode(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("encoded policy must not be blank");
    }
    String raw;
    try {
      raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Malformed sandbox policy encoding: " + e.getMessage(), e);
    }
    var builder = SandboxPolicy.newBuilder();
    for (var field : raw.split("\\|", -1)) {
      if (field.isEmpty()) {
        continue;
      }
      var eq = field.indexOf('=');
      if (eq < 0) {
        throw new IllegalArgumentException(
            "Malformed sandbox policy field (missing '='): " + field);
      }
      var key = field.substring(0, eq);
      var value = field.substring(eq + 1);
      applyField(builder, key, value);
    }
    return builder.build();
  }

  private static void applyField(SandboxPolicy.Builder builder, String key, String value) {
    switch (key) {
      case FIELD_DENIED_CLASSES -> builder.withDeniedClasses(splitList(value));
      case FIELD_DENIED_PACKAGES -> builder.withDeniedPackages(splitList(value));
      case FIELD_DENY_REFLECTION -> builder.withDenyReflection(Boolean.parseBoolean(value));
      case FIELD_DENY_NATIVE -> builder.withDenyNativeAccess(Boolean.parseBoolean(value));
      case FIELD_DENY_DYN_CLASS_DEF ->
          builder.withDenyDynamicClassDefinition(Boolean.parseBoolean(value));
      case FIELD_ON_VIOLATION -> builder.withOnViolation(ViolationAction.valueOf(value));
      default -> {}
    }
  }

  private static void appendField(StringBuilder sb, String key, String value) {
    if (!sb.isEmpty()) {
      sb.append('|');
    }
    sb.append(key).append('=').append(value);
  }

  private static String joinSorted(Set<String> values) {
    return values.stream().sorted().reduce((a, b) -> a + "," + b).orElse("");
  }

  private static Set<String> splitList(String value) {
    if (value.isEmpty()) {
      return Set.of();
    }
    var out = new LinkedHashSet<String>();
    for (var token : value.split(",", -1)) {
      if (!token.isEmpty()) {
        out.add(token);
      }
    }
    return out;
  }
}

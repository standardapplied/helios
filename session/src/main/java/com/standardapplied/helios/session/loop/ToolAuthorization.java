/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.RedactionResult;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The mandatory permission stage of a tool call, between the {@code PreToolUse} hooks and dispatch:
 * it canonicalises the call's path argument, authorises the canonical call on its key, and decides
 * the exact call that runs, the canonical one. It fails closed, so only an {@link
 * PermissionEffect#ALLOW} decision admits the call. A refusal's reason, like every reason the round
 * refuses a call with, is a diagnostic: redacted through the session's secrets and bounded.
 */
final class ToolAuthorization {

  static final int MAX_REASON_CHARS = 1024;

  /** The predicate of a call that withholds nothing. */
  static final Predicate<String> NOTHING_WITHHELD = key -> false;

  private static final Set<PermissionEffect> OWN_RULES = Set.of(PermissionEffect.DENY);
  private static final Set<PermissionEffect> CONTENT_RULES =
      Set.of(PermissionEffect.DENY, PermissionEffect.ASK);

  private ToolAuthorization() {}

  /** What the stage decided for a call. */
  sealed interface Verdict {}

  /**
   * The call runs as given, canonical for a registered tool.
   *
   * @param call the call to dispatch
   * @param withheld true for the canonical key of an entry the call must not return
   */
  record Admitted(ToolCall call, Predicate<String> withheld) implements Verdict {}

  /**
   * The call does not run.
   *
   * @param reason why, unredacted
   */
  record Refused(String reason) implements Verdict {}

  /**
   * Decide {@code call}: canonicalise it through its binding and authorise the canonical call. A
   * call {@code tools} binds no tool for is admitted unchanged and dispatch answers it {@code
   * NotFound}. A call whose canonicalisation throws, or whose key carries a secret of {@code
   * secrets}, is refused before {@code authorization} sees it, so no approval prompt or event shows
   * the secret.
   *
   * @param tools the registry dispatch looks the call up in
   * @param authorization the session's permission check of a canonical call
   * @param ruleDecision the decision of the policy rule that decides a key, if one does
   * @param secrets the session's secrets, which no permission key may carry
   * @param call the final call
   * @return the canonical call with what it withholds, or why it is refused
   */
  static Verdict admit(
      ToolRegistry tools,
      Function<ToolBinding.CanonicalCall, PermissionDecision> authorization,
      Function<ToolPermissionKey, Optional<PermissionDecision>> ruleDecision,
      SecretRegistry secrets,
      ToolCall call) {
    var registered = tools.get(call.name());
    if (registered.isEmpty()) {
      return new Admitted(call, NOTHING_WITHHELD);
    }
    var binding = registered.orElseThrow();
    ToolBinding.CanonicalCall canonical;
    try {
      canonical = binding.canonicalize(call);
    } catch (RuntimeException e) {
      return new Refused(call.name() + ": " + e.getMessage());
    }
    var key = canonical.key();
    if (secrets.leaks(key.toolName()) || secrets.leaks(key.canonicalArgs())) {
      return new Refused("permission key of " + call.name() + " carries a secret");
    }
    return refusal(authorization, canonical)
        .<Verdict>map(Refused::new)
        .orElseGet(() -> new Admitted(canonical.call(), withheld(binding, ruleDecision)));
  }

  private static Optional<String> refusal(
      Function<ToolBinding.CanonicalCall, PermissionDecision> authorization,
      ToolBinding.CanonicalCall canonical) {
    var name = canonical.call().name();
    PermissionDecision decision;
    try {
      decision = authorization.apply(canonical);
    } catch (RuntimeException e) {
      return Optional.of(
          "permission check of "
              + name
              + " threw "
              + e.getClass().getName()
              + ": "
              + e.getMessage());
    }
    if (decision == null) {
      return Optional.of("no permission decision for " + name);
    }
    return decision.effect() == PermissionEffect.ALLOW
        ? Optional.empty()
        : Optional.of(decision.reason());
  }

  /**
   * True for a key that a DENY rule of the call's tool decides, or a DENY or ASK rule of a tool its
   * path argument names in {@link ToolBinding.PathArgument#contentRulesOf}.
   */
  private static Predicate<String> withheld(
      ToolBinding binding, Function<ToolPermissionKey, Optional<PermissionDecision>> ruleDecision) {
    var contentTools =
        binding.pathArgument().map(ToolBinding.PathArgument::contentRulesOf).orElse(Set.of());
    return key ->
        decidedBy(ruleDecision, binding.name(), key, OWN_RULES)
            || contentTools.stream()
                .anyMatch(tool -> decidedBy(ruleDecision, tool, key, CONTENT_RULES));
  }

  private static boolean decidedBy(
      Function<ToolPermissionKey, Optional<PermissionDecision>> ruleDecision,
      String tool,
      String key,
      Set<PermissionEffect> effects) {
    return ruleDecision
        .apply(new ToolPermissionKey(tool, key))
        .filter(decision -> effects.contains(decision.effect()))
        .isPresent();
  }

  /**
   * {@code reason} with every registered secret redacted, cut after {@link #MAX_REASON_CHARS}
   * characters (never inside a surrogate pair) with a note of how much was cut, and the count of
   * each secret redacted.
   */
  static RedactionResult diagnostic(SecretRegistry secrets, String reason) {
    var redaction = secrets.redactor().redact(reason);
    var cut = Strings.truncated(redaction.text(), MAX_REASON_CHARS, "… [%d more chars]");
    return new RedactionResult(cut.getBytes(StandardCharsets.UTF_8), redaction.counts());
  }
}

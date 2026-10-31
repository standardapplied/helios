/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.session.hooks.HookOutcome;

/**
 * A hook's outcome as {@link com.standardapplied.helios.session.QueryEvent.HookFired HookFired}
 * shows it: every registered secret redacted in each string it carries, a {@link
 * HookOutcome.MutateArgs}'s arguments as {@link RedactedArguments} shows them, and a {@link
 * HookOutcome.MutateHistory} as returned, since its messages are the session's own.
 */
final class RedactedHookOutcome {

  private RedactedHookOutcome() {}

  static HookOutcome of(HookOutcome outcome, SecretRegistry secrets) {
    var redactor = secrets.redactor();
    return switch (outcome) {
      case HookOutcome.Continue _, HookOutcome.MutateHistory _ -> outcome;
      case HookOutcome.MutateArgs mutate ->
          HookOutcome.mutateArgs(RedactedArguments.of(mutate.args(), secrets));
      case HookOutcome.MutateText mutate -> HookOutcome.mutateText(text(mutate.text(), redactor));
      case HookOutcome.MutateResult mutate ->
          HookOutcome.mutateResult(text(mutate.output(), redactor));
      case HookOutcome.Block block -> HookOutcome.block(text(block.reason(), redactor));
      case HookOutcome.Inject inject -> HookOutcome.inject(text(inject.userMessage(), redactor));
      case HookOutcome.Stop stop -> HookOutcome.stop(text(stop.result(), redactor));
    };
  }

  private static String text(String text, Redactor redactor) {
    return redactor.redact(text).text();
  }
}

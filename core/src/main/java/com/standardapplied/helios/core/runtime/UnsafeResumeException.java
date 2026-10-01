/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.util.List;

/**
 * Thrown when {@code Agent.resume(...)} is called under {@link UnsafeResumePolicy#FAIL_LOUD} and
 * the journal contains one or more {@link ToolCallStatus#STARTED} entries for tools that were not
 * declared idempotent.
 *
 * <p>The carried list of {@link ToolCallRecord}s lets the caller surface every unsafe call to the
 * operator at once — partial recovery decisions can then be made per-call (mark succeeded manually,
 * mark failed and retry, abandon the run).
 */
public class UnsafeResumeException extends RuntimeException {

  private final transient List<ToolCallRecord> unsafeCalls;

  public UnsafeResumeException(List<ToolCallRecord> unsafeCalls) {
    super(buildMessage(unsafeCalls));
    this.unsafeCalls = List.copyOf(unsafeCalls);
  }

  /** Returns the in-flight non-idempotent tool calls that blocked the resume. */
  public List<ToolCallRecord> unsafeCalls() {
    return unsafeCalls;
  }

  private static String buildMessage(List<ToolCallRecord> calls) {
    var names = calls.stream().map(c -> c.toolName() + "[" + c.toolCallId() + "]").toList();
    return "Cannot safely resume run; "
        + calls.size()
        + " non-idempotent tool call(s) were in-flight at crash: "
        + String.join(", ", names);
  }
}

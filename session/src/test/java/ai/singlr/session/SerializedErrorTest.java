/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SerializedErrorTest {

  @Test
  void canonicalConstructorAcceptsAllFields() {
    var err = new SerializedError("kind", "msg", List.of("frame1", "frame2"), null);
    assertEquals("kind", err.kind());
    assertEquals("msg", err.message());
    assertEquals(List.of("frame1", "frame2"), err.stackTrace());
  }

  @Test
  void nullStackTraceBecomesEmptyList() {
    var err = new SerializedError("kind", "msg", null, null);
    assertEquals(List.of(), err.stackTrace());
  }

  @Test
  void stackTraceIsDefensivelyCopied() {
    var mutable = new ArrayList<>(List.of("a", "b"));
    var err = new SerializedError("kind", "msg", mutable, null);
    mutable.add("c");
    assertEquals(List.of("a", "b"), err.stackTrace());
  }

  @Test
  void stackTraceIsImmutableAfterConstruction() {
    var err = new SerializedError("kind", "msg", List.of("a"), null);
    assertThrows(UnsupportedOperationException.class, () -> err.stackTrace().add("b"));
  }

  @Test
  void nullKindThrowsNullPointerException() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new SerializedError(null, "msg", null, null));
    assertEquals("kind must not be null", ex.getMessage());
  }

  @Test
  void blankKindThrowsIllegalArgumentException() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new SerializedError(" ", "msg", null, null));
    assertEquals("kind must not be blank", ex.getMessage());
  }

  @Test
  void nullMessageThrowsNullPointerException() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> new SerializedError("kind", null, null, null));
    assertEquals("message must not be null", ex.getMessage());
  }

  @Test
  void emptyMessageAllowed() {
    var err = new SerializedError("kind", "", null, null);
    assertEquals("", err.message());
  }

  @Test
  void ofKindMessageFactoryHasEmptyStackTrace() {
    var err = SerializedError.of("interrupted", "user requested stop");
    assertEquals("interrupted", err.kind());
    assertEquals("user requested stop", err.message());
    assertEquals(List.of(), err.stackTrace());
  }

  @Test
  void ofThrowableCapturesClassNameMessageAndFrames() {
    var thrown = new IllegalStateException("boom");
    var err = SerializedError.of(thrown);
    assertEquals(IllegalStateException.class.getName(), err.kind());
    assertEquals("boom", err.message());
    assertNotNull(err.stackTrace());
    assertTrue(err.stackTrace().size() > 0, "expected at least one stack frame");
  }

  @Test
  void ofThrowableWithNullMessageProducesEmptyString() {
    var thrown = new RuntimeException();
    var err = SerializedError.of(thrown);
    assertEquals(RuntimeException.class.getName(), err.kind());
    assertEquals("", err.message());
  }

  @Test
  void ofNullThrowableThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> SerializedError.of((Throwable) null));
    assertEquals("throwable must not be null", ex.getMessage());
  }

  @Test
  void withoutStackTracePreservesKindAndMessage() {
    var err = new SerializedError("kind", "msg", List.of("frame1", "frame2", "frame3"), null);
    var redacted = err.withoutStackTrace();
    assertEquals("kind", redacted.kind());
    assertEquals("msg", redacted.message());
    assertEquals(List.of(), redacted.stackTrace());
  }

  @Test
  void withoutStackTraceReturnsSameInstanceWhenAlreadyEmpty() {
    var err = SerializedError.of("kind", "msg");
    assertTrue(
        err == err.withoutStackTrace(),
        "no-op fast path — avoid allocation when there's nothing to redact");
  }

  @Test
  void withoutStackTraceLeavesOriginalAlone() {
    var err = new SerializedError("kind", "msg", List.of("frame1"), null);
    err.withoutStackTrace();
    assertEquals(
        List.of("frame1"),
        err.stackTrace(),
        "withoutStackTrace must return a new record, never mutate the original");
  }

  // ── cause chain ────────────────────────────────────────────────────────────

  @Test
  void ofThrowableCapturesNestedCauseChain() {
    // The motivating bug: ReplException("Failed to spawn", ioException) where ioException's
    // message ("No such file or directory: target/cron.jar") is what the deployer actually needs.
    var root = new java.io.IOException("No such file or directory: target/cron.jar");
    var mid = new RuntimeException("Failed to spawn", root);
    var outer = new IllegalStateException("session start refused", mid);

    var err = SerializedError.of(outer);

    assertEquals(IllegalStateException.class.getName(), err.kind());
    assertEquals("session start refused", err.message());
    assertNotNull(err.cause(), "outer error must carry its cause");
    assertEquals(RuntimeException.class.getName(), err.cause().kind());
    assertEquals("Failed to spawn", err.cause().message());
    assertNotNull(err.cause().cause(), "mid-level cause must carry its own cause");
    assertEquals(java.io.IOException.class.getName(), err.cause().cause().kind());
    assertEquals("No such file or directory: target/cron.jar", err.cause().cause().message());
    assertNull(err.cause().cause().cause(), "root cause has no further cause");
  }

  @Test
  void causeOptEmptyWhenNoCause() {
    var err = SerializedError.of(new RuntimeException("solo"));
    assertTrue(err.causeOpt().isEmpty(), "throwable with no cause should yield Optional.empty");
  }

  @Test
  void causeOptPresentWhenCauseSet() {
    var inner = new java.io.IOException("inner");
    var outer = new RuntimeException("outer", inner);
    var err = SerializedError.of(outer);
    assertTrue(err.causeOpt().isPresent());
    assertEquals(java.io.IOException.class.getName(), err.causeOpt().get().kind());
  }

  @Test
  void ofThrowableBreaksSelfReferentialCauseChain() {
    // Belt-and-braces: a cyclic cause chain (Throwable that lists itself as its own cause) would
    // otherwise stack-overflow the recursive ofChain. Java's setCause() forbids this at runtime
    // (throws IllegalArgumentException), so we exercise the other half of the guard: a 32-deep
    // chain truncates at MAX_CAUSE_DEPTH=16 without throwing.
    Throwable t = new RuntimeException("level-32");
    for (int i = 31; i >= 0; i--) {
      t = new RuntimeException("level-" + i, t);
    }
    var err = SerializedError.of(t);
    var depth = 0;
    var cur = err;
    while (cur != null) {
      depth++;
      cur = cur.cause();
    }
    assertTrue(
        depth <= 17,
        "chain truncates at MAX_CAUSE_DEPTH=16 + the root = 17 levels; observed " + depth);
  }

  @Test
  void withoutStackTraceStripsRecursivelyAcrossCauseChain() {
    var root = new RuntimeException("root");
    var outer = new RuntimeException("outer", root);
    var err = SerializedError.of(outer);
    assertTrue(err.stackTrace().size() > 0);
    assertTrue(err.cause().stackTrace().size() > 0);

    var stripped = err.withoutStackTrace();

    assertEquals(List.of(), stripped.stackTrace(), "outer frames stripped");
    assertEquals(List.of(), stripped.cause().stackTrace(), "cause frames stripped too");
    assertEquals("outer", stripped.message(), "messages preserved");
    assertEquals("root", stripped.cause().message(), "cause message preserved");
  }
}

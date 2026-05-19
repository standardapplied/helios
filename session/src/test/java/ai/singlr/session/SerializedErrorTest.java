/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SerializedErrorTest {

  @Test
  void canonicalConstructorAcceptsAllFields() {
    var err = new SerializedError("kind", "msg", List.of("frame1", "frame2"));
    assertEquals("kind", err.kind());
    assertEquals("msg", err.message());
    assertEquals(List.of("frame1", "frame2"), err.stackTrace());
  }

  @Test
  void nullStackTraceBecomesEmptyList() {
    var err = new SerializedError("kind", "msg", null);
    assertEquals(List.of(), err.stackTrace());
  }

  @Test
  void stackTraceIsDefensivelyCopied() {
    var mutable = new ArrayList<>(List.of("a", "b"));
    var err = new SerializedError("kind", "msg", mutable);
    mutable.add("c");
    assertEquals(List.of("a", "b"), err.stackTrace());
  }

  @Test
  void stackTraceIsImmutableAfterConstruction() {
    var err = new SerializedError("kind", "msg", List.of("a"));
    assertThrows(UnsupportedOperationException.class, () -> err.stackTrace().add("b"));
  }

  @Test
  void nullKindThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> new SerializedError(null, "msg", null));
    assertEquals("kind must not be null", ex.getMessage());
  }

  @Test
  void blankKindThrowsIllegalArgumentException() {
    var ex =
        assertThrows(IllegalArgumentException.class, () -> new SerializedError(" ", "msg", null));
    assertEquals("kind must not be blank", ex.getMessage());
  }

  @Test
  void nullMessageThrowsNullPointerException() {
    var ex =
        assertThrows(NullPointerException.class, () -> new SerializedError("kind", null, null));
    assertEquals("message must not be null", ex.getMessage());
  }

  @Test
  void emptyMessageAllowed() {
    var err = new SerializedError("kind", "", null);
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
    var err = new SerializedError("kind", "msg", List.of("frame1", "frame2", "frame3"));
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
    var err = new SerializedError("kind", "msg", List.of("frame1"));
    err.withoutStackTrace();
    assertEquals(
        List.of("frame1"),
        err.stackTrace(),
        "withoutStackTrace must return a new record, never mutate the original");
  }
}

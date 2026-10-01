/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class InMemoryFileTrackerTest {

  private static final FileFingerprint FP_A = new FileFingerprint(Instant.EPOCH, 3, "a".repeat(64));
  private static final FileFingerprint FP_B =
      new FileFingerprint(Instant.EPOCH.plusSeconds(1), 4, "b".repeat(64));

  @Test
  void emptyTrackerHasNoReadsOrWrites() {
    var tracker = InMemoryFileTracker.create();
    assertFalse(tracker.hasReadInSession(Path.of("/x")));
    assertTrue(tracker.fingerprintAtLastRead(Path.of("/x")).isEmpty());
    assertTrue(tracker.fingerprintAtLastWrite(Path.of("/x")).isEmpty());
    assertEquals(0, tracker.readCount());
    assertEquals(0, tracker.writeCount());
  }

  @Test
  void recordReadIsObservable() {
    var tracker = InMemoryFileTracker.create();
    tracker.recordRead(Path.of("/x"), FP_A);
    assertTrue(tracker.hasReadInSession(Path.of("/x")));
    assertEquals(FP_A, tracker.fingerprintAtLastRead(Path.of("/x")).orElseThrow());
    assertEquals(1, tracker.readCount());
  }

  @Test
  void laterReadOverwritesPriorFingerprint() {
    var tracker = InMemoryFileTracker.create();
    tracker.recordRead(Path.of("/x"), FP_A);
    tracker.recordRead(Path.of("/x"), FP_B);
    assertEquals(FP_B, tracker.fingerprintAtLastRead(Path.of("/x")).orElseThrow());
    assertEquals(1, tracker.readCount());
  }

  @Test
  void recordWriteIsObservable() {
    var tracker = InMemoryFileTracker.create();
    tracker.recordWrite(Path.of("/x"), FP_A);
    assertEquals(FP_A, tracker.fingerprintAtLastWrite(Path.of("/x")).orElseThrow());
    assertEquals(1, tracker.writeCount());
  }

  @Test
  void writesAreSeparateFromReads() {
    var tracker = InMemoryFileTracker.create();
    tracker.recordRead(Path.of("/x"), FP_A);
    tracker.recordWrite(Path.of("/x"), FP_B);
    assertEquals(FP_A, tracker.fingerprintAtLastRead(Path.of("/x")).orElseThrow());
    assertEquals(FP_B, tracker.fingerprintAtLastWrite(Path.of("/x")).orElseThrow());
  }

  @Test
  void recordReadRejectsNulls() {
    var tracker = InMemoryFileTracker.create();
    assertThrows(NullPointerException.class, () -> tracker.recordRead(null, FP_A));
    assertThrows(NullPointerException.class, () -> tracker.recordRead(Path.of("/x"), null));
  }

  @Test
  void recordWriteRejectsNulls() {
    var tracker = InMemoryFileTracker.create();
    assertThrows(NullPointerException.class, () -> tracker.recordWrite(null, FP_A));
    assertThrows(NullPointerException.class, () -> tracker.recordWrite(Path.of("/x"), null));
  }

  @Test
  void hasReadInSessionRejectsNull() {
    var tracker = InMemoryFileTracker.create();
    assertThrows(NullPointerException.class, () -> tracker.hasReadInSession(null));
  }

  @Test
  void fingerprintAtLastReadRejectsNull() {
    var tracker = InMemoryFileTracker.create();
    assertThrows(NullPointerException.class, () -> tracker.fingerprintAtLastRead(null));
  }

  @Test
  void fingerprintAtLastWriteRejectsNull() {
    var tracker = InMemoryFileTracker.create();
    assertThrows(NullPointerException.class, () -> tracker.fingerprintAtLastWrite(null));
  }
}

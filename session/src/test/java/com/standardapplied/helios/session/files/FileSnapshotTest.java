/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class FileSnapshotTest {

  private static final FileFingerprint FP = new FileFingerprint(Instant.EPOCH, 3, "0".repeat(64));

  @Test
  void canonicalConstructorClonesContent() {
    var bytes = new byte[] {1, 2, 3};
    var snap = new FileSnapshot(Path.of("x"), bytes, FP);
    bytes[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3}, snap.content());
  }

  @Test
  void contentAccessorReturnsDefensiveCopy() {
    var snap = new FileSnapshot(Path.of("x"), new byte[] {1, 2, 3}, FP);
    var first = snap.content();
    var second = snap.content();
    assertNotSame(first, second);
    first[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3}, snap.content());
  }

  @Test
  void rejectsNullPath() {
    assertThrows(NullPointerException.class, () -> new FileSnapshot(null, new byte[0], FP));
  }

  @Test
  void rejectsNullContent() {
    assertThrows(NullPointerException.class, () -> new FileSnapshot(Path.of("x"), null, FP));
  }

  @Test
  void rejectsNullFingerprint() {
    assertThrows(
        NullPointerException.class, () -> new FileSnapshot(Path.of("x"), new byte[0], null));
  }

  @Test
  void emptyContentIsAllowed() {
    var snap = new FileSnapshot(Path.of("x"), new byte[0], FP);
    assertEquals(0, snap.content().length);
    assertEquals(Path.of("x"), snap.path());
    assertEquals(FP, snap.fingerprint());
  }
}

/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileFingerprintTest {

  private static final String SAMPLE_SHA = "a".repeat(64);

  @Test
  void canonicalConstructorAccepts64HexChars() {
    var fp = new FileFingerprint(Instant.EPOCH, 0, SAMPLE_SHA);
    assertEquals(SAMPLE_SHA, fp.sha256());
    assertEquals(0, fp.size());
    assertEquals(Instant.EPOCH, fp.mtime());
  }

  @Test
  void rejectsNullMtime() {
    assertThrows(NullPointerException.class, () -> new FileFingerprint(null, 0, SAMPLE_SHA));
  }

  @Test
  void rejectsNullSha() {
    assertThrows(NullPointerException.class, () -> new FileFingerprint(Instant.EPOCH, 0, null));
  }

  @Test
  void rejectsNegativeSize() {
    assertThrows(
        IllegalArgumentException.class, () -> new FileFingerprint(Instant.EPOCH, -1, SAMPLE_SHA));
  }

  @Test
  void rejectsShortSha() {
    assertThrows(
        IllegalArgumentException.class, () -> new FileFingerprint(Instant.EPOCH, 0, "abc"));
  }

  @Test
  void rejectsLongSha() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileFingerprint(Instant.EPOCH, 0, "a".repeat(65)));
  }

  @Test
  void rejectsUppercaseSha() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileFingerprint(Instant.EPOCH, 0, "A".repeat(64)));
  }

  @Test
  void rejectsNonHexChar() {
    var bad = "g".repeat(64);
    assertThrows(IllegalArgumentException.class, () -> new FileFingerprint(Instant.EPOCH, 0, bad));
  }

  @Test
  void ofReadsFileBytes(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("hello.txt");
    Files.writeString(file, "hello", StandardCharsets.UTF_8);
    var fp = FileFingerprint.of(file);
    assertNotNull(fp);
    assertEquals(5, fp.size());
    // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", fp.sha256());
  }

  @Test
  void ofRejectsNullPath() {
    assertThrows(NullPointerException.class, () -> FileFingerprint.of(null));
  }

  @Test
  void ofPropagatesIoErrorForMissingFile(@TempDir Path tmp) {
    var missing = tmp.resolve("nope.txt");
    assertThrows(IOException.class, () -> FileFingerprint.of(missing));
  }

  @Test
  void distinctContentsProduceDifferentHashes(@TempDir Path tmp) throws IOException {
    var a = tmp.resolve("a.txt");
    var b = tmp.resolve("b.txt");
    Files.writeString(a, "one", StandardCharsets.UTF_8);
    Files.writeString(b, "two", StandardCharsets.UTF_8);
    assertNotEquals(FileFingerprint.of(a).sha256(), FileFingerprint.of(b).sha256());
  }

  @Test
  void ofHandlesFilesLargerThanReadBuffer(@TempDir Path tmp) throws IOException {
    // 1 MB of repeating bytes — crosses the 64 KB read-buffer boundary many times to exercise
    // the streaming digest. Compare the streamed fingerprint against an in-memory SHA-256 of the
    // same bytes to prove byte-for-byte equivalence.
    var file = tmp.resolve("big.bin");
    var content = new byte[1_048_576];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) (i & 0xff);
    }
    Files.write(file, content);
    var fp = FileFingerprint.of(file);
    assertEquals(content.length, fp.size());
    try {
      var expected =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
      assertEquals(expected, fp.sha256());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void ofProducesEmptyHashForEmptyFile(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("empty.bin");
    Files.createFile(file);
    var fp = FileFingerprint.of(file);
    assertEquals(0L, fp.size());
    // sha256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fp.sha256());
  }
}

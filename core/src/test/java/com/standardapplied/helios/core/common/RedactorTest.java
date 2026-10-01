/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RedactorTest {

  private static Redactor build(String... namesAndValues) {
    var registry = new SecretRegistry();
    for (var i = 0; i < namesAndValues.length; i += 2) {
      registry.register(namesAndValues[i], namesAndValues[i + 1]);
    }
    return registry.redactor();
  }

  @Test
  void emptyRegistryPassesInputThrough() {
    var redactor = Redactor.of(Map.of());
    var result = redactor.redact("hello world");
    assertEquals("hello world", result.text());
    assertEquals(0, result.totalRedactions());
    assertTrue(result.counts().isEmpty());
  }

  @Test
  void nullInputBytesProducesEmptyResult() {
    var redactor = build("T", "secrettoken123");
    var result = redactor.redact((byte[]) null);
    assertEquals("", result.text());
    assertEquals(0, result.totalRedactions());
  }

  @Test
  void nullInputStringProducesEmptyResult() {
    var redactor = build("T", "secrettoken123");
    var result = redactor.redact((String) null);
    assertEquals("", result.text());
  }

  @Test
  void emptyInputProducesEmptyResult() {
    var redactor = build("T", "secrettoken123");
    var result = redactor.redact("");
    assertEquals("", result.text());
  }

  @Test
  void singleSecretRedactedWithMarker() {
    var redactor = build("T", "ghp_abc12345");
    var result = redactor.redact("token=ghp_abc12345 here");
    assertEquals("token=<redacted:T> here", result.text());
    assertEquals(1, result.totalRedactions());
    assertEquals(Map.of("T", 1), result.counts());
  }

  @Test
  void multipleOccurrencesAllRedacted() {
    var redactor = build("T", "ghp_abc12345");
    var result = redactor.redact("ghp_abc12345 again ghp_abc12345");
    assertEquals("<redacted:T> again <redacted:T>", result.text());
    assertEquals(2, result.totalRedactions());
  }

  @Test
  void unrelatedTextPassesThroughUnchanged() {
    var redactor = build("T", "ghp_abc12345");
    var result = redactor.redact("nothing to see here");
    assertEquals("nothing to see here", result.text());
    assertEquals(0, result.totalRedactions());
    assertTrue(result.counts().isEmpty());
  }

  @Test
  void multipleSecretsTrackedSeparately() {
    var redactor = build("T1", "alphaalpha", "T2", "betabetab");
    var result = redactor.redact("alphaalpha and betabetab and alphaalpha");
    assertEquals("<redacted:T1> and <redacted:T2> and <redacted:T1>", result.text());
    assertEquals(2, (int) result.counts().get("T1"));
    assertEquals(1, (int) result.counts().get("T2"));
    assertEquals(3, result.totalRedactions());
  }

  @Test
  void overlappingSecretsResolveLeftmostLongest() {
    var redactor = build("LONG", "abcdefgh", "SHORT", "cdefghij");
    var result = redactor.redact("abcdefghij");
    assertEquals("<redacted:LONG>ij", result.text());
    assertEquals(1, result.totalRedactions());
  }

  @Test
  void sameStartTakesLongerMatch() {
    var redactor = build("SHORT", "abcdefgh", "LONG", "abcdefghij");
    var result = redactor.redact("abcdefghij rest");
    assertEquals("<redacted:LONG> rest", result.text());
  }

  @Test
  void suffixSecretInsideLongerMatchSuppressed() {
    var redactor = build("OUTER", "abcdefgh", "INNER", "cdefghij");
    var result = redactor.redact("abcdefghij");
    assertEquals("<redacted:OUTER>ij", result.text());
  }

  @Test
  void nestedSuffixMatchEmittedWhenNonOverlapping() {
    var redactor = build("OUTER", "abcdefghij", "INNER", "cdefghij");
    var result = redactor.redact("xxabcdefghij and cdefghij again");
    assertEquals("xx<redacted:OUTER> and <redacted:INNER> again", result.text());
  }

  @Test
  void duplicateValueAttributesToFirstName() {
    var redactor = build("FIRST", "samevalue1", "SECOND", "samevalue1");
    var result = redactor.redact("samevalue1");
    assertEquals("<redacted:FIRST>", result.text());
    assertFalse(result.counts().containsKey("SECOND"));
  }

  @Test
  void nonAsciiBytesInInputDoNotMaskMatch() {
    var redactor = build("T", "abcdefgh");
    var prefix = new byte[] {(byte) 0xC3, (byte) 0xA9};
    var middle = "abcdefgh".getBytes(StandardCharsets.US_ASCII);
    var suffix = new byte[] {(byte) 0xE2, (byte) 0x98, (byte) 0x83};
    var input = new byte[prefix.length + middle.length + suffix.length];
    System.arraycopy(prefix, 0, input, 0, prefix.length);
    System.arraycopy(middle, 0, input, prefix.length, middle.length);
    System.arraycopy(suffix, 0, input, prefix.length + middle.length, suffix.length);
    var result = redactor.redact(input);
    assertTrue(result.text().contains("<redacted:T>"));
    assertFalse(result.text().contains("abcdefgh"));
    assertEquals(1, result.totalRedactions());
  }

  @Test
  void nonAsciiByteBreaksPartialMatch() {
    var redactor = build("T", "abcdefgh");
    var input = new byte[] {'a', 'b', 'c', (byte) 0x80, 'd', 'e', 'f', 'g', 'h'};
    var result = redactor.redact(input);
    assertFalse(result.text().contains("<redacted:T>"));
    assertEquals(0, result.totalRedactions());
  }

  @Test
  void secretAtBufferStart() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("abcdefgh tail");
    assertEquals("<redacted:T> tail", result.text());
  }

  @Test
  void secretAtBufferEnd() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("head abcdefgh");
    assertEquals("head <redacted:T>", result.text());
  }

  @Test
  void secretSpanningEntireInput() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("abcdefgh");
    assertEquals("<redacted:T>", result.text());
  }

  @Test
  void backToBackOccurrencesRedactedSeparately() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("abcdefghabcdefgh");
    assertEquals("<redacted:T><redacted:T>", result.text());
    assertEquals(2, result.totalRedactions());
  }

  @Test
  void selfOverlappingPatternMatchesNonOverlappingOnly() {
    var redactor = build("T", "abcabcab");
    var result = redactor.redact("abcabcabcabcab");
    assertEquals("<redacted:T>cabcab", result.text());
    assertEquals(1, result.totalRedactions());
  }

  @Test
  void redactorOfRefusesNonAsciiBytes() {
    var bad = new LinkedHashMap<String, byte[]>();
    bad.put("T", new byte[] {'a', 'b', (byte) 0x80, 'd', 'e', 'f', 'g', 'h'});
    var ex = assertThrows(IllegalArgumentException.class, () -> Redactor.of(bad));
    assertTrue(ex.getMessage().contains("ASCII"));
  }

  @Test
  void redactorOfNullMapReturnsEmpty() {
    var redactor = Redactor.of(null);
    assertEquals(0, redactor.patternCount());
    assertEquals("anything", redactor.redact("anything").text());
  }

  @Test
  void redactorReturnsFreshArrayPerCall() {
    var redactor = build("T", "abcdefgh");
    var result1 = redactor.redact("safe");
    var result2 = redactor.redact("safe");
    assertNotNull(result1.bytes());
    assertNotNull(result2.bytes());
    assertFalse(result1.bytes() == result2.bytes());
  }

  @Test
  void multibytePatternsCannotBeRegisteredViaRegistry() {
    var registry = new SecretRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.register("T", "valueé12345"));
  }

  @Test
  void totalRedactionsHandlesEmptyCounts() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("nothing");
    assertEquals(0, result.totalRedactions());
  }

  @Test
  void manySecretsAllMatched() {
    var registry = new SecretRegistry();
    for (var i = 0; i < 50; i++) {
      registry.register("T%02d".formatted(i), "secretvaluexx%02d".formatted(i));
    }
    var redactor = registry.redactor();
    assertEquals(50, redactor.patternCount());
    var input = "secretvaluexx00 then secretvaluexx25 then secretvaluexx49 then unrelated";
    var result = redactor.redact(input);
    assertEquals(3, result.totalRedactions());
    assertTrue(result.text().contains("<redacted:T00>"));
    assertTrue(result.text().contains("<redacted:T25>"));
    assertTrue(result.text().contains("<redacted:T49>"));
    assertFalse(result.text().contains("secretvaluexx"));
  }

  @Test
  void propertyRandomSecretsRandomPositions() {
    var seed = 0xC0FFEEL;
    var rng = new Random(seed);
    for (var trial = 0; trial < 200; trial++) {
      var registry = new SecretRegistry();
      var secretCount = 1 + rng.nextInt(5);
      var secretValues = new java.util.ArrayList<String>();
      for (var s = 0; s < secretCount; s++) {
        var len = 8 + rng.nextInt(20);
        var sb = new StringBuilder(len);
        for (var k = 0; k < len; k++) {
          sb.append((char) ('a' + rng.nextInt(26)));
        }
        var value = sb.toString();
        secretValues.add(value);
        registry.register("S" + s, value);
      }
      var noise = randomAscii(rng, 50 + rng.nextInt(200));
      var insertions = rng.nextInt(8);
      var input = new StringBuilder(noise);
      for (var k = 0; k < insertions; k++) {
        var which = rng.nextInt(secretValues.size());
        var pos = rng.nextInt(input.length() + 1);
        input.insert(pos, secretValues.get(which));
      }
      var redactor = registry.redactor();
      var result = redactor.redact(input.toString());
      for (var v : secretValues) {
        assertFalse(
            result.text().contains(v),
            "trial=%d found secret %s in: %s".formatted(trial, v, result.text()));
      }
    }
  }

  @Test
  void propertyAllOccurrencesAccountedFor() {
    var rng = new Random(0xBADC0DEL);
    for (var trial = 0; trial < 100; trial++) {
      var registry = new SecretRegistry();
      var secret = randomLower(rng, 8 + rng.nextInt(20));
      registry.register("ONE", secret);
      var noise = randomLowerNoMatch(rng, 200, secret);
      var occurrences = rng.nextInt(5);
      var sb = new StringBuilder(noise);
      for (var k = 0; k < occurrences; k++) {
        sb.insert(rng.nextInt(sb.length() + 1), secret);
      }
      var input = sb.toString();
      var expected = countLeftmostLongest(input, secret);
      var result = registry.redactor().redact(input);
      assertEquals(expected, result.totalRedactions(), "trial=" + trial);
      assertFalse(result.text().contains(secret));
      if (expected > 0) {
        assertEquals(expected, (int) result.counts().get("ONE"));
      }
    }
  }

  private static int countLeftmostLongest(String input, String pattern) {
    var count = 0;
    var i = 0;
    while (i + pattern.length() <= input.length()) {
      if (input.regionMatches(i, pattern, 0, pattern.length())) {
        count++;
        i += pattern.length();
      } else {
        i++;
      }
    }
    return count;
  }

  private static String randomLower(Random rng, int len) {
    var sb = new StringBuilder(len);
    for (var i = 0; i < len; i++) {
      sb.append((char) ('a' + rng.nextInt(26)));
    }
    return sb.toString();
  }

  @Test
  void redactionResultRecordExposesFields() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("abcdefgh");
    assertNotNull(result.bytes());
    assertEquals(Map.of("T", 1), result.counts());
  }

  @Test
  void counterMapImmutable() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("abcdefgh");
    assertThrows(UnsupportedOperationException.class, () -> result.counts().put("X", 1));
  }

  @Test
  void redactorPatternCountMatchesRegistration() {
    var registry = new SecretRegistry();
    registry.register("A", "alphaalpha");
    registry.register("B", "betabetab");
    assertEquals(2, registry.redactor().patternCount());
  }

  @Test
  void leaksByteScanRespectsAllCandidates() {
    var registry = new SecretRegistry();
    registry.register("A", "alphaalpha");
    registry.register("B", "betabetab");
    assertTrue(registry.leaks("noise alphaalpha noise"));
    assertTrue(registry.leaks("noise betabetab noise"));
    assertFalse(registry.leaks("noise gammagammagamma noise"));
  }

  @Test
  void sharedSuffixPatternsBothMatched() {
    var redactor = build("ABC", "alphabxx", "BC", "phabxxxx");
    var result = redactor.redact("xxalphabxxxxalphabxxxx");
    var counts = result.counts();
    assertEquals(2, counts.values().stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  void redactPreservesNonAsciiSurroundingBytes() {
    var redactor = build("T", "abcdefgh");
    var input = ("é" + "abcdefgh" + "☃").getBytes(StandardCharsets.UTF_8);
    var result = redactor.redact(input);
    var decoded = result.text();
    assertTrue(decoded.startsWith("é<redacted:T>"));
    assertTrue(decoded.endsWith("☃"));
  }

  @Test
  void worksFromBuildEvenWhenSomeSecretsAreNeverMatched() {
    var redactor = build("USED", "abcdefgh", "UNUSED", "qrstuvwx");
    var result = redactor.redact("only abcdefgh appears");
    assertEquals(1, result.totalRedactions());
    assertEquals(List.of("USED"), List.copyOf(result.counts().keySet()));
  }

  private static String randomAscii(Random rng, int len) {
    var sb = new StringBuilder(len);
    for (var i = 0; i < len; i++) {
      var c = (char) (32 + rng.nextInt(95));
      sb.append(c);
    }
    return sb.toString();
  }

  private static String randomLowerNoMatch(Random rng, int len, String forbidden) {
    while (true) {
      var sb = new StringBuilder(len);
      for (var i = 0; i < len; i++) {
        sb.append((char) ('a' + rng.nextInt(26)));
      }
      var s = sb.toString();
      if (!s.contains(forbidden)) {
        return s;
      }
    }
  }
}

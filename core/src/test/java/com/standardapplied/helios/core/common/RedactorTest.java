/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

  private static final String SECRET = "sk-test-0123456789abcdef";

  @Test
  void trimPartialSecretLeavesTextWithoutOneUnchanged() {
    assertEquals(
        "ends in sk-tes", build("OTHER", "zzzzzzzzzz").trimPartialSecret("ends in sk-tes"));
    assertEquals("plain text", build("API_KEY", SECRET).trimPartialSecret("plain text"));
  }

  @Test
  void trimPartialSecretDropsAOneCharacterPrefix() {
    assertEquals("cut at ", build("API_KEY", SECRET).trimPartialSecret("cut at s"));
  }

  @Test
  void trimPartialSecretDropsAPrefixOneShortOfTheWholeSecret() {
    var redactor = build("API_KEY", SECRET);

    assertEquals("cut at ", redactor.trimPartialSecret("cut at " + SECRET.substring(0, 23)));
  }

  @Test
  void trimPartialSecretLeavesACompleteSecretForRedaction() {
    var redactor = build("API_KEY", SECRET);

    assertEquals("key " + SECRET, redactor.trimPartialSecret("key " + SECRET));
  }

  @Test
  void trimPartialSecretLeavesACompleteSecretEndingInItsOwnPrefix() {
    var redactor = build("TOKEN", "abcd1234a");

    assertEquals("value=abcd1234a", redactor.trimPartialSecret("value=abcd1234a"));
    assertEquals(
        "value=abcd1234a",
        new String(
            redactor.trimPartialSecret("value=abcd1234a".getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8));
  }

  @Test
  void trimPartialSecretLeavesACompleteSecretEndingInAnotherSecretsPrefix() {
    var redactor = build("A", "abcdefgh12", "B", "h12345678");

    assertEquals("xx abcdefgh12", redactor.trimPartialSecret("xx abcdefgh12"));
  }

  @Test
  void trimPartialSecretDropsAPartialSecretReachingBeforeACompleteOne() {
    var redactor = build("A", "defgh123", "B", "abcdefgh123456");

    assertEquals("xx ", redactor.trimPartialSecret("xx abcdefgh123"));
  }

  @Test
  void trimPartialSecretDropsTheLongestOfOverlappingPrefixes() {
    var redactor = build("A", "abcdefgh12", "B", "fgh12345678");

    assertEquals("xx ", redactor.trimPartialSecret("xx abcdefgh1"));
  }

  @Test
  void trimPartialSecretDropsAPrefixThatDroppingAnOverlappingOneExposes() {
    var redactor = build("A", "abcdefgh12", "B", "fgh12345678");

    assertEquals("xx ", redactor.trimPartialSecret("xx abcdefgh12345"));
  }

  @Test
  void trimPartialSecretKeepsTextBeforeTheCutSecretThatOnlyLooksLikeItsStart() {
    var redactor = build("API_KEY", SECRET);

    assertEquals("tasks: sk-", redactor.trimPartialSecret("tasks: sk-" + SECRET.substring(0, 10)));
  }

  @Test
  void trimPartialSecretDropsOnlyTheCutSecretFromARepeatingRun() {
    var redactor = build("TOKEN", "aaaaaaaa1");

    assertEquals("a".repeat(992), redactor.trimPartialSecret("a".repeat(1000)));
  }

  @Test
  void trimPartialSecretLeavesAnInputThatIsOneCompleteSecret() {
    assertEquals("abcd1234a", build("TOKEN", "abcd1234a").trimPartialSecret("abcd1234a"));
  }

  @Test
  void trimPartialSecretLeavesACompleteSecretThatStartsALongerOne() {
    var redactor = build("SHORT", "abcd1234", "LONG", "abcd1234wxyz");

    assertEquals("key abcd1234", redactor.trimPartialSecret("key abcd1234"));
  }

  @Test
  void trimPartialSecretKeepsACompleteSecretBeforeTheCutOne() {
    var redactor = build("API_KEY", SECRET, "OTHER", "zz-other-token");

    assertEquals("key " + SECRET, redactor.trimPartialSecret("key " + SECRET + "zz-oth"));
    assertEquals("key " + SECRET, redactor.trimPartialSecret("key " + SECRET + "sk-te"));
  }

  @Test
  void trimPartialSecretDropsACompleteSecretAtTheEndThatAnotherSecretOverlaps() {
    var redactor = build("A", "xyz-1234", "S", "1234-key", "T", "key-9999");

    var kept = redactor.trimPartialSecret("id xyz-1234-key");

    assertEquals("id ", kept);
    assertEquals("id <redacted:A>", redactor.redact("id xyz-1234-key").text());
  }

  @Test
  void trimPartialSecretOnBytesReturnsAFreshArray() {
    var input = ("cut at " + SECRET.substring(0, 10)).getBytes(StandardCharsets.UTF_8);
    var copy = input.clone();

    var trimmed = build("API_KEY", SECRET).trimPartialSecret(input);

    assertEquals("cut at ", new String(trimmed, StandardCharsets.UTF_8));
    assertArrayEquals(copy, input);
  }

  @Test
  void trimPartialSecretKeepsNonAsciiTextBeforeTheCut() {
    assertEquals("héllo ", build("API_KEY", SECRET).trimPartialSecret("héllo sk-"));
  }

  @Test
  void emptyRegistryPassesInputThrough() {
    var redactor = Redactor.of(Map.of());
    var result = redactor.redact("hello world");
    assertEquals("hello world", result.text());
    assertTrue(result.counts().isEmpty());
  }

  @Test
  void nullInputStringProducesEmptyResult() {
    var redactor = build("T", "secrettoken123");
    var result = redactor.redact(null);
    assertEquals("", result.text());
    assertTrue(result.counts().isEmpty());
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
    assertEquals(Map.of("T", 1), result.counts());
  }

  @Test
  void multipleOccurrencesAllRedacted() {
    var redactor = build("T", "ghp_abc12345");
    var result = redactor.redact("ghp_abc12345 again ghp_abc12345");
    assertEquals("<redacted:T> again <redacted:T>", result.text());
    assertEquals(Map.of("T", 2), result.counts());
  }

  @Test
  void unrelatedTextPassesThroughUnchanged() {
    var redactor = build("T", "ghp_abc12345");
    var input = "nothing to see here";
    var result = redactor.redact(input);
    assertSame(input, result.text());
    assertTrue(result.counts().isEmpty());
  }

  @Test
  void multipleSecretsTrackedSeparately() {
    var redactor = build("T1", "alphaalpha", "T2", "betabetab");
    var result = redactor.redact("alphaalpha and betabetab and alphaalpha");
    assertEquals("<redacted:T1> and <redacted:T2> and <redacted:T1>", result.text());
    assertEquals(2, (int) result.counts().get("T1"));
    assertEquals(1, (int) result.counts().get("T2"));
  }

  @Test
  void overlappingSecretsBecomeOneMarkerNamingTheFirst() {
    var redactor = build("LONG", "abcdefgh", "SHORT", "cdefghij");
    var result = redactor.redact("abcdefghij");
    assertEquals("<redacted:LONG>", result.text());
    assertEquals(Map.of("LONG", 1), result.counts());
  }

  @Test
  void noCharacterOfEitherOverlappingSecretSurvives() {
    var input = "xxxxxxxxYYzzzzzzzz";
    for (var redactor :
        List.of(
            build("A", "xxxxxxxxYY", "B", "YYzzzzzzzz"),
            build("B", "YYzzzzzzzz", "A", "xxxxxxxxYY"))) {
      var result = redactor.redact(input);
      assertEquals("<redacted:A>", result.text());
      assertEquals(Map.of("A", 1), result.counts());
    }
  }

  @Test
  void aMarkerAtAnEqualStartNamesTheLongerSecretAndHidesTheShorter() {
    var result = build("SHORT", "abcdefgh", "LONG", "abcdefghij").redact("abcdefghij");
    assertEquals(Map.of("LONG", 1), result.counts());
  }

  @Test
  void aSecretInsideALongerOnesSpanDoesNotShortenIt() {
    var redactor = build("OUTER", "abcdefghijklmnop", "INNER", "cdefghij", "TAIL", "lmnopqrs");
    var result = redactor.redact("abcdefghijklmnopqrs rest");
    assertEquals("<redacted:OUTER> rest", result.text());
    assertEquals(Map.of("OUTER", 1), result.counts());
  }

  @Test
  void sameStartTakesLongerMatch() {
    var redactor = build("SHORT", "abcdefgh", "LONG", "abcdefghij");
    var result = redactor.redact("abcdefghij rest");
    assertEquals("<redacted:LONG> rest", result.text());
  }

  @Test
  void aSecretOverlappingTheEndOfAnotherIsHiddenByItsMarker() {
    var redactor = build("OUTER", "abcdefgh", "INNER", "cdefghij");
    var result = redactor.redact("abcdefghij");
    assertEquals("<redacted:OUTER>", result.text());
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
    var result = redactor.redact("éabcdefgh☃");
    assertTrue(result.text().contains("<redacted:T>"));
    assertFalse(result.text().contains("abcdefgh"));
    assertEquals(Map.of("T", 1), result.counts());
  }

  @Test
  void aNonAsciiNameAppearsInItsMarkerAsRegistered() {
    assertEquals("<redacted:clé>", build("clé", "abcdefgh").redact("abcdefgh").text());
  }

  @Test
  void anUnpairedSurrogateSurvivesRedaction() {
    var redactor = build("K", "abcdefgh");
    assertEquals("a\uD800b <redacted:K>", redactor.redact("a\uD800b abcdefgh").text());
    assertEquals("a\uD800b", redactor.redact("a\uD800b").text());
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
    assertEquals(Map.of("T", 2), result.counts());
  }

  @Test
  void selfOverlappingOccurrencesBecomeOneMarker() {
    var redactor = build("T", "abcabcab");
    var result = redactor.redact("abcabcabcabcab");
    assertEquals("<redacted:T>", result.text());
    assertEquals(Map.of("T", 1), result.counts());
  }

  @Test
  void redactorOfRefusesANonAsciiValue() {
    var bad = Map.of("T", "ab\u0080defgh");
    var ex = assertThrows(IllegalArgumentException.class, () -> Redactor.of(bad));
    assertTrue(ex.getMessage().contains("'T'"));
    assertTrue(ex.getMessage().contains("ASCII"));
  }

  @Test
  void redactorOfRefusesANullValue() {
    var bad = new LinkedHashMap<String, String>();
    bad.put("T", null);
    var ex = assertThrows(IllegalArgumentException.class, () -> Redactor.of(bad));
    assertEquals("Secret 'T' must not be empty", ex.getMessage());
  }

  @Test
  void redactorOfNullMapReturnsEmpty() {
    assertEquals("anything", Redactor.of(null).redact("anything").text());
  }

  @Test
  void multibytePatternsCannotBeRegisteredViaRegistry() {
    var registry = new SecretRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.register("T", "valueé12345"));
  }

  @Test
  void manySecretsAllMatched() {
    var registry = new SecretRegistry();
    for (var i = 0; i < 50; i++) {
      registry.register("T%02d".formatted(i), "secretvaluexx%02d".formatted(i));
    }
    var redactor = registry.redactor();
    var input = "secretvaluexx00 then secretvaluexx25 then secretvaluexx49 then unrelated";
    var result = redactor.redact(input);
    assertEquals(3, result.counts().size());
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
      var expected = countMergedSpans(input, secret);
      var result = registry.redactor().redact(input);
      assertEquals(expected == 0 ? Map.of() : Map.of("ONE", expected), result.counts());
      assertFalse(result.text().contains(secret));
    }
  }

  private static int countMergedSpans(String input, String pattern) {
    var count = 0;
    var spanEnd = 0;
    for (var at = input.indexOf(pattern); at >= 0; at = input.indexOf(pattern, at + 1)) {
      if (at >= spanEnd) {
        count++;
      }
      spanEnd = at + pattern.length();
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
    assertEquals("<redacted:T>", result.text());
    assertEquals(Map.of("T", 1), result.counts());
  }

  @Test
  void aResultKeepsTheCountsItWasBuiltWith() {
    var counts = new LinkedHashMap<String, Integer>();
    counts.put("B", 1);
    counts.put("A", 2);
    var result = new RedactionResult("text", counts);
    counts.clear();
    assertEquals(List.of("B", "A"), List.copyOf(result.counts().keySet()));
  }

  @Test
  void counterMapImmutable() {
    var redactor = build("T", "abcdefgh");
    var result = redactor.redact("abcdefgh");
    assertThrows(UnsupportedOperationException.class, () -> result.counts().put("X", 1));
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
  void overlappingSpansThatTouchStayTwoMarkers() {
    var redactor = build("ABC", "alphabxx", "BC", "phabxxxx");
    var result = redactor.redact("xxalphabxxxxalphabxxxx");
    assertEquals("xx<redacted:ABC><redacted:ABC>", result.text());
    assertEquals(Map.of("ABC", 2), result.counts());
  }

  @Test
  void redactPreservesNonAsciiSurroundingBytes() {
    var redactor = build("T", "abcdefgh");
    var input = "é" + "abcdefgh" + "☃";
    var result = redactor.redact(input);
    var decoded = result.text();
    assertTrue(decoded.startsWith("é<redacted:T>"));
    assertTrue(decoded.endsWith("☃"));
  }

  @Test
  void worksFromBuildEvenWhenSomeSecretsAreNeverMatched() {
    var redactor = build("USED", "abcdefgh", "UNUSED", "qrstuvwx");
    var result = redactor.redact("only abcdefgh appears");
    assertEquals(Map.of("USED", 1), result.counts());
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

  @Test
  void aLaterChangeToTheGivenMapDoesNotReachTheRedactor() {
    var secrets = new LinkedHashMap<String, String>();
    secrets.put("K", "abcdefgh");
    var redactor = Redactor.of(secrets);

    secrets.put("K", "ijklmnop");
    secrets.put("L", "qrstuvwx");

    assertEquals("<redacted:K> ijklmnop", redactor.redact("abcdefgh ijklmnop").text());
  }

  @Test
  void theLastAsciiCharacterIsAccepted() {
    var redactor = Redactor.of(Map.of("K", "abc\u007Fdefg"));

    assertEquals("<redacted:K>", redactor.redact("abc\u007Fdefg").text());
  }

  @Test
  void aNullNameIsRejected() {
    var secrets = new LinkedHashMap<String, String>();
    secrets.put(null, "abcdefgh");

    var ex = assertThrows(NullPointerException.class, () -> Redactor.of(secrets));
    assertEquals("secret name must not be null", ex.getMessage());
  }

  @Test
  void aResultRequiresItsText() {
    var ex = assertThrows(NullPointerException.class, () -> new RedactionResult(null, Map.of()));
    assertEquals("text must not be null", ex.getMessage());
  }
}

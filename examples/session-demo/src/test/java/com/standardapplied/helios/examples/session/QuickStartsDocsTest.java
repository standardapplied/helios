/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds every README {@code java} block that calls {@code SessionPresets} to the body of the {@link
 * QuickStarts} method named by the marker on the line before it, so a quick start that drifts from
 * the API it documents fails the build.
 */
final class QuickStartsDocsTest {

  private static final Path QUICK_STARTS =
      Path.of("src/test/java/com/standardapplied/helios/examples/session/QuickStarts.java");
  private static final Pattern MARKER = Pattern.compile("<!-- snippet: QuickStarts#(\\w+) -->");
  private static final Pattern METHOD = Pattern.compile(" {2}static void (\\w+)\\(.*");
  private static final String BLOCK = "var options = SessionPresets.minimal(model);";

  @ParameterizedTest
  @ValueSource(strings = {"README.md", "session/README.md"})
  void everyPresetsBlockIsItsQuickStartsMethod(String readme) throws IOException {
    var lines = Files.readAllLines(Path.of("../..").resolve(readme));

    check(readme, lines, bodies(Files.readAllLines(QUICK_STARTS)));
  }

  static Stream<Arguments> drifts() {
    return Stream.of(
        Arguments.of(
            List.of(
                "<!-- snippet: QuickStarts#minimal -->",
                "```java",
                BLOCK.replace("model", "modal"),
                "```"),
            "README.md:2: QuickStarts#minimal"),
        Arguments.of(
            List.of("Intro", "```java", "var model = model();", BLOCK, "```"),
            "README.md:2: a SessionPresets block has no <!-- snippet: QuickStarts#<method> -->"),
        Arguments.of(
            List.of("- Intro", "  ```java ", "  " + BLOCK, "  ```"),
            "README.md:2: a SessionPresets block has no <!-- snippet: QuickStarts#<method> -->"),
        Arguments.of(
            List.of("<!-- snippet: QuickStarts#missing -->", "```java", BLOCK, "```"),
            "README.md:2: QuickStarts#missing does not exist"),
        Arguments.of(List.of("```java", BLOCK), "README.md:1: the block is never closed"));
  }

  @ParameterizedTest
  @MethodSource("drifts")
  void aDriftedBlockFailsNamingIt(List<String> readme, String expected) {
    var bodies = Map.of("minimal", List.of(BLOCK));

    var thrown = assertThrows(AssertionError.class, () -> check("README.md", readme, bodies));

    assertTrue(thrown.getMessage().contains(expected), thrown.getMessage());
  }

  private static void check(String readme, List<String> lines, Map<String, List<String>> bodies) {
    var blocks = new ArrayList<Executable>();
    for (var start = 0; start < lines.size(); start++) {
      if (lines.get(start).strip().equals("```java")) {
        var where = readme + ":" + (start + 1);
        var end = start + 1;
        while (end < lines.size() && !lines.get(end).strip().equals("```")) {
          end++;
        }
        assertTrue(end < lines.size(), where + ": the block is never closed");
        var block = lines.subList(start + 1, end);
        var marker = MARKER.matcher(start == 0 ? "" : lines.get(start - 1));
        if (marker.matches()) {
          var method = "QuickStarts#" + marker.group(1);
          var body = bodies.get(marker.group(1));
          blocks.add(
              () -> {
                assertNotNull(body, where + ": " + method + " does not exist");
                assertEquals(body, block, where + ": " + method);
              });
        } else if (block.stream().anyMatch(line -> line.contains("SessionPresets"))) {
          blocks.add(
              () ->
                  fail(
                      where
                          + ": a SessionPresets block has no"
                          + " <!-- snippet: QuickStarts#<method> --> marker"));
        }
        start = end;
      }
    }
    assertAll(blocks);
  }

  private static Map<String, List<String>> bodies(List<String> source) {
    var bodies = new HashMap<String, List<String>>();
    for (var i = 0; i < source.size(); i++) {
      var method = METHOD.matcher(source.get(i));
      if (method.matches()) {
        var open = i;
        while (!source.get(open).endsWith("{")) {
          open++;
        }
        var end = source.subList(open, source.size()).indexOf("  }") + open;
        bodies.put(method.group(1), dedented(source.subList(open + 1, end)));
        i = end;
      }
    }
    return bodies;
  }

  private static List<String> dedented(List<String> lines) {
    var indent =
        lines.stream()
            .filter(line -> !line.isBlank())
            .mapToInt(line -> line.length() - line.stripLeading().length())
            .min()
            .orElse(0);
    return lines.stream().map(line -> line.isBlank() ? "" : line.substring(indent)).toList();
  }
}

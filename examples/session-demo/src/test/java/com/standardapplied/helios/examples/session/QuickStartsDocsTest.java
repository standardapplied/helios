/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Holds every README {@code java} block that calls {@code SessionPresets} to the body of the {@link
 * QuickStarts} method named by the marker on the line before it, so a quick start that drifts from
 * the API it documents fails the build.
 */
final class QuickStartsDocsTest {

  private static final Path REPOSITORY = Path.of("../..");
  private static final List<String> READMES = List.of("README.md", "session/README.md");
  private static final Path QUICK_STARTS =
      Path.of("src/test/java/com/standardapplied/helios/examples/session/QuickStarts.java");
  private static final Pattern MARKER = Pattern.compile("<!-- snippet: QuickStarts#(\\w+) -->");
  private static final Pattern METHOD = Pattern.compile(" {2}static void (\\w+)\\(.*");
  private static final String OPENING_FENCE = "```java";
  private static final String CLOSING_FENCE = "```";
  private static final String METHOD_END = "  }";

  private static final Map<String, List<String>> SAMPLE_BODIES =
      Map.of("minimal", List.of("var options = SessionPresets.minimal(model);"));

  @Test
  void everyPresetsBlockInTheReadmesIsItsQuickStartsMethod() throws IOException {
    var bodies = bodies(Files.readAllLines(QUICK_STARTS));
    var problems = new ArrayList<String>();
    for (var readme : READMES) {
      problems.addAll(problems(readme, Files.readAllLines(REPOSITORY.resolve(readme)), bodies));
    }

    assertEquals(List.of(), problems);
  }

  @Test
  void aBodyIsTheMethodsTextWithoutItsCommonIndentation() {
    var source =
        List.of(
            "final class QuickStarts {",
            "  static void session(",
            "      Model model) {",
            "    try (var session = open(model)) {",
            "",
            "      session.run();",
            "    }",
            "  }",
            "}");

    assertEquals(
        Map.of(
            "session", List.of("try (var session = open(model)) {", "", "  session.run();", "}")),
        bodies(source));
  }

  @Test
  void aMatchingBlockHasNoProblem() {
    var readme =
        List.of(
            "<!-- snippet: QuickStarts#minimal -->",
            OPENING_FENCE,
            "var options = SessionPresets.minimal(model);",
            CLOSING_FENCE);

    assertEquals(List.of(), problems("README.md", readme, SAMPLE_BODIES));
  }

  @Test
  void aChangedCharacterNamesTheBlockAndTheLine() {
    var readme =
        List.of(
            "Intro",
            "<!-- snippet: QuickStarts#minimal -->",
            OPENING_FENCE,
            "var options = SessionPresets.minimal(modal);",
            CLOSING_FENCE);

    assertEquals(
        List.of(
            "README.md:3: line 1 differs from QuickStarts#minimal:"
                + " README `var options = SessionPresets.minimal(modal);`,"
                + " QuickStarts `var options = SessionPresets.minimal(model);`"),
        problems("README.md", readme, SAMPLE_BODIES));
  }

  @Test
  void aBlockLongerThanItsMethodNamesTheFirstExtraLine() {
    var readme =
        List.of(
            "<!-- snippet: QuickStarts#minimal -->",
            OPENING_FENCE,
            "var options = SessionPresets.minimal(model);",
            "options.build();",
            CLOSING_FENCE);

    assertEquals(
        List.of(
            "README.md:2: line 2 differs from QuickStarts#minimal:"
                + " README `options.build();`, QuickStarts `<no line>`"),
        problems("README.md", readme, SAMPLE_BODIES));
  }

  @Test
  void aPresetsBlockWithoutAMarkerIsNamed() {
    var readme =
        List.of(
            "Intro", OPENING_FENCE, "var options = SessionPresets.minimal(model);", CLOSING_FENCE);

    assertEquals(
        List.of(
            "session/README.md:2: a SessionPresets block has no"
                + " <!-- snippet: QuickStarts#<method> --> marker"),
        problems("session/README.md", readme, SAMPLE_BODIES));
  }

  @Test
  void aMarkerNamingNoMethodIsNamed() {
    var readme =
        List.of(
            "<!-- snippet: QuickStarts#missing -->",
            OPENING_FENCE,
            "var options = SessionPresets.minimal(model);",
            CLOSING_FENCE);

    assertEquals(
        List.of("README.md:2: QuickStarts#missing does not exist"),
        problems("README.md", readme, SAMPLE_BODIES));
  }

  @Test
  void aBlockThatDoesNotCallPresetsNeedsNoMarker() {
    var readme =
        List.of(OPENING_FENCE, "var options = SessionOptions.newBuilder();", CLOSING_FENCE);

    assertEquals(List.of(), problems("README.md", readme, SAMPLE_BODIES));
  }

  @Test
  void anUnclosedBlockFails() {
    var readme = List.of("Intro", OPENING_FENCE, "var options = SessionPresets.minimal(model);");

    var thrown =
        assertThrows(AssertionError.class, () -> problems("README.md", readme, SAMPLE_BODIES));

    assertEquals("README.md:2: the block is never closed", thrown.getMessage());
  }

  private static List<String> problems(
      String readme, List<String> lines, Map<String, List<String>> bodies) {
    var problems = new ArrayList<String>();
    for (var start = 0; start < lines.size(); start++) {
      if (lines.get(start).equals(OPENING_FENCE)) {
        var where = readme + ":" + (start + 1);
        var end = closingFence(lines, start, where);
        var marker = start == 0 ? "" : lines.get(start - 1);
        problem(where, marker, lines.subList(start + 1, end), bodies).ifPresent(problems::add);
        start = end;
      }
    }
    return problems;
  }

  private static int closingFence(List<String> lines, int start, String where) {
    return IntStream.range(start + 1, lines.size())
        .filter(i -> lines.get(i).equals(CLOSING_FENCE))
        .findFirst()
        .orElseThrow(() -> new AssertionError(where + ": the block is never closed"));
  }

  private static Optional<String> problem(
      String where, String marker, List<String> block, Map<String, List<String>> bodies) {
    var named = MARKER.matcher(marker);
    if (!named.matches()) {
      return block.stream().anyMatch(line -> line.contains("SessionPresets"))
          ? Optional.of(
              where
                  + ": a SessionPresets block has no <!-- snippet: QuickStarts#<method> --> marker")
          : Optional.empty();
    }
    var method = named.group(1);
    var body = bodies.get(method);
    if (body == null) {
      return Optional.of(where + ": QuickStarts#" + method + " does not exist");
    }
    return IntStream.range(0, Math.max(block.size(), body.size()))
        .filter(i -> !lineAt(block, i).equals(lineAt(body, i)))
        .boxed()
        .findFirst()
        .map(
            i ->
                where
                    + ": line "
                    + (i + 1)
                    + " differs from QuickStarts#"
                    + method
                    + ": README `"
                    + lineAt(block, i)
                    + "`, QuickStarts `"
                    + lineAt(body, i)
                    + "`");
  }

  private static String lineAt(List<String> lines, int index) {
    return index < lines.size() ? lines.get(index) : "<no line>";
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
        var end = source.subList(open, source.size()).indexOf(METHOD_END) + open;
        bodies.put(method.group(1), dedented(source.subList(open + 1, end)));
        i = end;
      }
    }
    return Map.copyOf(bodies);
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

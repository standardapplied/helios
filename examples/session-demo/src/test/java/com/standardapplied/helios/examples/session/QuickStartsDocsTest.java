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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds every {@code java} block of the README, the module READMEs and the guides under {@code
 * docs/} to the body of the snippet method named by the marker on the line before it, a {@code <!--
 * snippet: <Class>#<method> -->} naming a class of this package, so a block that drifts from the
 * API it documents fails the build. It also holds the README's install block to the newest release
 * in {@code CHANGELOG.md}.
 */
final class QuickStartsDocsTest {

  private static final Path ROOT = Path.of("../..");
  private static final Path SNIPPETS =
      Path.of("src/test/java/com/standardapplied/helios/examples/session");
  private static final Pattern MARKER = Pattern.compile("<!-- snippet: (\\w+#\\w+) -->");
  private static final Pattern METHOD = Pattern.compile(" {2}static void (\\w+)\\(.*");
  private static final String BLOCK = "var options = SessionPresets.minimal(model);";
  private static final Pattern INSTALLED =
      Pattern.compile("\\s*<helios\\.version>(.+)</helios\\.version>");
  private static final Pattern RELEASE = Pattern.compile("## \\[(.+?)].*");

  static Stream<String> documents() throws IOException {
    try (var guides = Files.list(ROOT.resolve("docs"))) {
      var docs =
          guides
              .map(guide -> "docs/" + guide.getFileName())
              .filter(name -> name.endsWith(".md"))
              .sorted()
              .toList();
      return Stream.concat(Stream.of("README.md", "session/README.md"), docs.stream());
    }
  }

  @ParameterizedTest
  @MethodSource("documents")
  void everyJavaBlockIsItsSnippetMethod(String document) throws IOException {
    var lines = Files.readAllLines(ROOT.resolve(document));

    check(document, lines, bodies(lines));
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
            "README.md:2: a java block has no <!-- snippet: <Class>#<method> -->"),
        Arguments.of(
            List.of("- Intro", "  ```java ", "  " + BLOCK, "  ```"),
            "README.md:2: a java block has no <!-- snippet: <Class>#<method> -->"),
        Arguments.of(
            List.of("<!-- snippet: QuickStarts#missing -->", "```java", BLOCK, "```"),
            "README.md:2: QuickStarts#missing does not exist"),
        Arguments.of(
            List.of("<!-- snippet: Missing#minimal -->", "```java", BLOCK, "```"),
            "README.md:2: Missing#minimal does not exist"),
        Arguments.of(List.of("```java", BLOCK), "README.md:1: the block is never closed"));
  }

  @ParameterizedTest
  @MethodSource("drifts")
  void aDriftedBlockFailsNamingIt(List<String> readme, String expected) {
    var bodies = Map.of("QuickStarts#minimal", List.of(BLOCK));

    var thrown = assertThrows(AssertionError.class, () -> check("README.md", readme, bodies));

    assertTrue(thrown.getMessage().contains(expected), thrown.getMessage());
  }

  @ParameterizedTest
  @MethodSource("installs")
  void theInstallBlockNamesTheNewestRelease(
      List<String> readme, List<String> changelog, String expected) {
    assertEquals(expected, installMismatch(readme, changelog));
  }

  static Stream<Arguments> installs() throws IOException {
    var release =
        List.of("# Changelog", "## Unreleased", "## [3.1.3] — title", "## [3.1.2] — title");
    return Stream.of(
        Arguments.of(
            Files.readAllLines(ROOT.resolve("README.md")),
            Files.readAllLines(ROOT.resolve("CHANGELOG.md")),
            ""),
        Arguments.of(List.of("  <helios.version>3.1.3</helios.version>"), release, ""),
        Arguments.of(
            List.of("  <helios.version>3.1.2</helios.version>"),
            release,
            "README.md installs 3.1.2, CHANGELOG.md's newest release is 3.1.3"),
        Arguments.of(
            List.of("no install block"),
            release,
            "README.md installs nothing, CHANGELOG.md's newest release is 3.1.3"));
  }

  /** Empty when the README's install block names CHANGELOG.md's newest release, else why not. */
  private static String installMismatch(List<String> readme, List<String> changelog) {
    var installed = first(readme, INSTALLED, "nothing");
    var released = first(changelog, RELEASE, "none");
    return installed.equals(released)
        ? ""
        : "README.md installs " + installed + ", CHANGELOG.md's newest release is " + released;
  }

  private static String first(List<String> lines, Pattern pattern, String none) {
    return lines.stream()
        .map(pattern::matcher)
        .filter(Matcher::matches)
        .map(match -> match.group(1))
        .findFirst()
        .orElse(none);
  }

  private static void check(String document, List<String> lines, Map<String, List<String>> bodies) {
    var blocks = new ArrayList<Executable>();
    for (var start = 0; start < lines.size(); start++) {
      if (lines.get(start).strip().equals("```java")) {
        var where = document + ":" + (start + 1);
        var end = start + 1;
        while (end < lines.size() && !lines.get(end).strip().equals("```")) {
          end++;
        }
        assertTrue(end < lines.size(), where + ": the block is never closed");
        var block = lines.subList(start + 1, end);
        var marker = MARKER.matcher(start == 0 ? "" : lines.get(start - 1));
        if (marker.matches()) {
          var method = marker.group(1);
          var body = bodies.get(method);
          blocks.add(
              () -> {
                assertNotNull(body, where + ": " + method + " does not exist");
                assertEquals(body, block, where + ": " + method);
              });
        } else {
          blocks.add(
              () ->
                  fail(where + ": a java block has no <!-- snippet: <Class>#<method> --> marker"));
        }
        start = end;
      }
    }
    assertAll(blocks);
  }

  private static Map<String, List<String>> bodies(List<String> document) throws IOException {
    var bodies = new HashMap<String, List<String>>();
    var types =
        document.stream()
            .map(MARKER::matcher)
            .filter(Matcher::matches)
            .map(marker -> marker.group(1).substring(0, marker.group(1).indexOf('#')))
            .distinct()
            .toList();
    for (var type : types) {
      var source = SNIPPETS.resolve(type + ".java");
      if (Files.exists(source)) {
        bodies.putAll(bodies(type, source));
      }
    }
    return bodies;
  }

  private static Map<String, List<String>> bodies(String type, Path source) throws IOException {
    var lines = Files.readAllLines(source);
    var bodies = new HashMap<String, List<String>>();
    for (var i = 0; i < lines.size(); i++) {
      var method = METHOD.matcher(lines.get(i));
      if (method.matches()) {
        var open = i;
        while (!lines.get(open).endsWith("{")) {
          open++;
        }
        var end = lines.subList(open, lines.size()).indexOf("  }") + open;
        bodies.put(type + "#" + method.group(1), dedented(lines.subList(open + 1, end)));
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

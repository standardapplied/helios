/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds every relative link of the README, the module READMEs, the guides under {@code docs/},
 * {@code AGENTS.md}, {@code RELEASE.md} and the unreleased section of {@code CHANGELOG.md} to an
 * existing file and, when it names one, a heading of that file, so a moved file or heading fails
 * the build. A heading's anchor is GitHub's: lower case, punctuation dropped, each space a hyphen,
 * and a repeated heading suffixed {@code -1}, {@code -2} and so on. Links inside code are not
 * links.
 */
final class DocsLinksTest {

  private static final Path ROOT = Path.of("../..");
  private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
  private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
  private static final Pattern HEADING = Pattern.compile("#{1,6}\\s+(.+?)\\s*#*\\s*");
  private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:.*");
  private static final Pattern NOT_IN_ANCHOR = Pattern.compile("[^\\p{L}\\p{N}\\p{M} _-]");
  private static final String UNRELEASED = "## Unreleased";

  private record Section(int from, int to) {}

  static Stream<String> documents() throws IOException {
    var documents =
        new ArrayList<>(List.of("README.md", "AGENTS.md", "RELEASE.md", "CHANGELOG.md"));
    try (var entries = Files.list(ROOT)) {
      entries
          .filter(entry -> Files.isRegularFile(entry.resolve("README.md")))
          .map(entry -> entry.getFileName() + "/README.md")
          .forEach(documents::add);
    }
    try (var guides = Files.list(ROOT.resolve("docs"))) {
      guides
          .map(guide -> "docs/" + guide.getFileName())
          .filter(name -> name.endsWith(".md"))
          .forEach(documents::add);
    }
    return documents.stream().sorted();
  }

  @ParameterizedTest
  @MethodSource("documents")
  void everyRelativeLinkResolves(String document) throws IOException {
    var lines = Files.readAllLines(ROOT.resolve(document));

    assertEquals(List.of(), broken(document, lines));
  }

  static Stream<Arguments> links() {
    return Stream.of(
        Arguments.of("[a](docs/sessions.md) [b](docs/sessions.md#events) [c](#top)", List.of()),
        Arguments.of("[a](https://example.com/x#y) [b](mailto:a@example.com)", List.of()),
        Arguments.of("[a](docs/missing.md)", List.of("README.md:3: docs/missing.md")),
        Arguments.of(
            "[a](docs/sessions.md#missing)", List.of("README.md:3: docs/sessions.md#missing")),
        Arguments.of("[a](#missing)", List.of("README.md:3: #missing")),
        Arguments.of("[a](#top-1)", List.of()),
        Arguments.of("`[a](docs/missing.md)` and [b](docs/)", List.of()),
        Arguments.of("[a](LICENSE#top)", List.of("README.md:3: LICENSE#top")));
  }

  @ParameterizedTest
  @MethodSource("links")
  void aLinkResolvesOnlyToAnExistingFileAndHeading(String line, List<String> expected) {
    var lines = List.of("# Top", "## Top", line, "```", "[a](docs/missing.md)", "```");

    assertEquals(expected, broken("README.md", lines));
  }

  private static List<String> broken(String document, List<String> lines) {
    var broken = new ArrayList<String>();
    var prose = prose(lines);
    var section = section(document, lines);
    for (var i = section.from(); i < section.to(); i++) {
      var link = LINK.matcher(CODE_SPAN.matcher(prose.get(i)).replaceAll(""));
      while (link.find()) {
        var target = link.group(1);
        if (!SCHEME.matcher(target).matches() && !resolves(document, lines, target)) {
          broken.add(document + ":" + (i + 1) + ": " + target);
        }
      }
    }
    return broken;
  }

  /** The lines whose links are checked: all of them, or only CHANGELOG's unreleased section. */
  private static Section section(String document, List<String> lines) {
    if (!document.equals("CHANGELOG.md")) {
      return new Section(0, lines.size());
    }
    var from = lines.indexOf(UNRELEASED);
    var to = from + 1;
    while (to < lines.size() && !lines.get(to).startsWith("## ")) {
      to++;
    }
    return new Section(from, to);
  }

  private static boolean resolves(String document, List<String> lines, String target) {
    var hash = target.indexOf('#');
    var path = hash < 0 ? target : target.substring(0, hash);
    if (path.isEmpty()) {
      return anchors(lines).contains(target.substring(hash + 1));
    }
    var file = ROOT.resolve(document).getParent().resolve(path).normalize();
    if (hash < 0) {
      return Files.exists(file);
    }
    return path.endsWith(".md")
        && Files.isRegularFile(file)
        && anchors(read(file)).contains(target.substring(hash + 1));
  }

  private static Set<String> anchors(List<String> lines) {
    var anchors = new HashSet<String>();
    var seen = new HashMap<String, Integer>();
    for (var line : prose(lines)) {
      var heading = HEADING.matcher(line);
      if (heading.matches()) {
        var slug =
            NOT_IN_ANCHOR
                .matcher(heading.group(1).toLowerCase(Locale.ROOT))
                .replaceAll("")
                .replace(' ', '-');
        var repeats = seen.merge(slug, 1, Integer::sum) - 1;
        anchors.add(repeats == 0 ? slug : slug + "-" + repeats);
      }
    }
    return anchors;
  }

  private static List<String> prose(List<String> lines) {
    var prose = new ArrayList<String>();
    var fenced = false;
    for (var line : lines) {
      var fence = line.strip().startsWith("```");
      fenced ^= fence;
      prose.add(fenced || fence ? "" : line);
    }
    return prose;
  }

  private static List<String> read(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

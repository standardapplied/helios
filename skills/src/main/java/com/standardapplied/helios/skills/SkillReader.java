/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import com.standardapplied.helios.session.Skill;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * Reads skill directories into {@link Skill} snapshots for one {@code discover} or {@code load}
 * call: the frontmatter and body of each {@code SKILL.md}, under the standard's lenient rules where
 * {@code Skill} allows them, and the text of each listed file, held in memory, so a loaded skill
 * never reads the disk again. A problem with a skill skips it with a warning. Stateful: it counts
 * the bytes of text the call keeps against {@link #MAX_LOADED_BYTES}.
 */
final class SkillReader {

  /** {@link FileSystemMemoryBackend#MAX_FILE_BYTES}, 1 MiB: the largest file read in full. */
  static final int MAX_SKILL_FILE_BYTES = FileSystemMemoryBackend.MAX_FILE_BYTES;

  /**
   * {@link ReadTool#MAX_OUTPUT_CHARS}, the largest page {@code Read} returns: a strict UTF-8 file
   * this size is at most as many characters, so the {@code Skill} tool serves it whole under the
   * default tool-result cap.
   */
  static final int MAX_BUNDLED_FILE_BYTES = ReadTool.MAX_OUTPUT_CHARS;

  /**
   * The bytes of text one call keeps, all skills together: sixteen {@code SKILL.md} files at their
   * cap, 16 MiB, so what a call holds stays bounded however many skills its roots hold.
   */
  static final long MAX_LOADED_BYTES = 16L * MAX_SKILL_FILE_BYTES;

  private static final YAMLMapper YAML =
      YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Pattern FENCE =
      Pattern.compile("^---[ \\t]*$", Pattern.MULTILINE | Pattern.UNIX_LINES);
  private static final String BYTE_ORDER_MARK = Character.toString(0xFEFF);

  private long left = MAX_LOADED_BYTES;

  /**
   * The skill in {@code directory} with the files {@code keys}, or empty, with a warning, when it
   * cannot be loaded or the call's budget runs out while reading it.
   */
  Optional<Skill> read(Path directory, List<String> keys) {
    var file = directory.resolve(SkillWalk.SKILL_FILE);
    try {
      var content = text(file, MAX_SKILL_FILE_BYTES);
      return spent() ? Optional.empty() : withFiles(parse(directory, content), directory, keys);
    } catch (IOException e) {
      return skipped(file, SkillLog.failed(e));
    } catch (JacksonException e) {
      return skipped(file, "its frontmatter is not valid YAML: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return skipped(file, e.getMessage());
    }
  }

  /** Whether the call's budget ran out; once it has, the call reads no further skill. */
  boolean spent() {
    return left < 0;
  }

  /** At most {@code cap} + 1 bytes of {@code in}, so a caller can tell a file over its cap. */
  static byte[] bytes(InputStream in, int cap) throws IOException {
    return in.readNBytes(cap + 1);
  }

  /**
   * {@code file}, opened without following a link, as strict UTF-8 text of at most {@code cap}
   * bytes, charged to the budget.
   *
   * @throws IllegalArgumentException if it is larger or not UTF-8
   */
  private String text(Path file, int cap) throws IOException {
    byte[] bytes;
    try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      bytes = bytes(in, cap);
    }
    if (bytes.length > cap) {
      throw new IllegalArgumentException("it is larger than " + cap + " bytes");
    }
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("it is not UTF-8", e);
    }
    left -= bytes.length;
    if (spent()) {
      SkillLog.warn(
          "Stopped loading skills at %s: it would pass the call's %s bytes of text; the skills"
              + " loaded before its skill are kept",
          file, MAX_LOADED_BYTES);
    }
    return text;
  }

  private static Optional<Skill> skipped(Path file, String reason) {
    SkillLog.warn("Skipping skill %s: %s", file, reason);
    return Optional.empty();
  }

  private static Skill parse(Path directory, String content) {
    var text = content.replace("\r\n", "\n");
    if (text.startsWith(BYTE_ORDER_MARK)) {
      text = text.substring(1);
    }
    var fences = FENCE.matcher(text);
    if (!fences.find() || fences.start() > 0) {
      throw new IllegalArgumentException("it does not start with a --- line");
    }
    var opening = fences.end();
    if (!fences.find()) {
      throw new IllegalArgumentException("its frontmatter has no closing --- line");
    }
    var frontmatter = frontmatter(text.substring(opening, fences.start()));
    var skill =
        Skill.newBuilder()
            .withName(string(frontmatter, "name"))
            .withDescription(string(frontmatter, "description"))
            .withInstructions(text.substring(fences.end()).strip())
            .withDirectory(directory.toString())
            .build();
    if (!directory.endsWith(skill.name())) {
      SkillLog.warn(
          "Skill %s at %s: its name differs from its directory's name; loading it",
          skill.name(), directory);
    }
    return skill;
  }

  /** The frontmatter as a tree; an alias would read as its anchor's name, so it is refused. */
  private static JsonNode frontmatter(String yaml) {
    try (var parser = (YAMLParser) YAML.createParser(yaml)) {
      while (parser.nextToken() != null) {
        if (parser.isCurrentAlias()) {
          throw new IllegalArgumentException(
              "its frontmatter uses a YAML alias, which skills do not support");
        }
      }
    }
    var frontmatter = YAML.readTree(yaml);
    if (!frontmatter.isObject()) {
      throw new IllegalArgumentException("its frontmatter is not a mapping");
    }
    return frontmatter;
  }

  private static String string(JsonNode frontmatter, String key) {
    var value = frontmatter.path(key);
    if (!value.isString()) {
      throw new IllegalArgumentException(
          "its frontmatter's " + key + " is missing or not a string");
    }
    return value.stringValue();
  }

  /**
   * {@code base} with each file of {@code keys} below {@code directory} that can be read and that
   * {@code Skill} accepts, or empty when the budget runs out on the way.
   */
  private Optional<Skill> withFiles(Skill base, Path directory, List<String> keys) {
    var files = new HashMap<String, Supplier<String>>();
    for (var key : keys) {
      try {
        var content = snapshot(directory.resolve(key), key);
        base.toBuilder().withFile(key, content).build();
        files.put(key, content);
      } catch (IOException e) {
        SkillLog.warn(
            "Skill %s: leaving out its file %s: %s", base.name(), key, SkillLog.failed(e));
      } catch (IllegalArgumentException e) {
        SkillLog.warn("Skill %s: leaving out its file %s: %s", base.name(), key, e.getMessage());
      }
      if (spent()) {
        return Optional.empty();
      }
    }
    return Optional.of(base.toBuilder().withFiles(files).build());
  }

  /** What reading {@code key} returns from now on: its text, or why it cannot be read as text. */
  private Supplier<String> snapshot(Path file, String key) throws IOException {
    try {
      var text = text(file, MAX_BUNDLED_FILE_BYTES);
      return () -> text;
    } catch (IllegalArgumentException e) {
      var message =
          "skill file %s: %s; use it at its path under the skill directory"
              .formatted(key, e.getMessage());
      return () -> {
        throw new UncheckedIOException(message, new IOException(message));
      };
    }
  }
}

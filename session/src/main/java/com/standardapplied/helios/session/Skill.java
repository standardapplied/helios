/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Validate;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * An Agent Skill (agentskills.io) a session offers its model, registered with {@link
 * SessionOptions.Builder#withSkills}: its name and description are listed in the session's system
 * prompt, and the built-in {@code Skill} tool returns its instructions and the list of its files,
 * or one file, when the model asks. A host builds it from anywhere: a {@code SKILL.md} folder, a
 * classpath resource or plain strings.
 *
 * <p>A file's supplier is called each time a {@code Skill} call reads that file, never cached and
 * never called for an activation; a supplier that throws, or returns null, ends that call as {@code
 * ToolOutcome.Threw}.
 *
 * @param name the skill's name, lowercase letters and digits in groups joined by single hyphens
 *     ({@code [a-z0-9]+(-[a-z0-9]+)*})
 * @param description what the skill is for and when to use it, listed in the system prompt;
 *     non-blank
 * @param instructions the skill's instructions, the body of its {@code SKILL.md}; non-null, may be
 *     empty
 * @param directory where the skill's files live as a tool that runs them names it, such as a
 *     folder-backed skill's absolute path; empty for a skill that is not on disk, non-blank when
 *     present
 * @param files the skill's bundled files by relative path, each read only when a call asks for it;
 *     copied. Every key is {@code /}-separated segments, none empty, {@code .} or {@code ..}, with
 *     no {@code \} and no ISO control character
 */
public record Skill(
    String name,
    String description,
    String instructions,
    Optional<String> directory,
    Map<String, Supplier<String>> files) {

  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument, a key or a value of {@code files} is null
   * @throws IllegalArgumentException if {@code name} breaks the naming rule, {@code description} is
   *     blank, {@code directory} is present and blank, or a key of {@code files} is no relative
   *     path
   */
  public Skill {
    Objects.requireNonNull(name, "name must not be null");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "skill name \"" + name + "\" must match " + NAME.pattern());
    }
    Validate.notBlank("description", description);
    Objects.requireNonNull(instructions, "instructions must not be null");
    Objects.requireNonNull(directory, "directory must not be null");
    directory.ifPresent(path -> Validate.notBlank("directory", path));
    files = Map.copyOf(Objects.requireNonNull(files, "files must not be null"));
    files.keySet().forEach(Skill::requireRelativePath);
  }

  /**
   * A skill with no directory and no files.
   *
   * @param name the skill's name
   * @param description what the skill is for
   * @param instructions its instructions
   * @return the skill
   * @throws NullPointerException if an argument is null
   * @throws IllegalArgumentException if an argument breaks its rule
   */
  public static Skill of(String name, String description, String instructions) {
    return new Skill(name, description, instructions, Optional.empty(), Map.of());
  }

  private static void requireRelativePath(String path) {
    var valid =
        path.chars().noneMatch(c -> c == '\\' || Character.isISOControl(c))
            && Arrays.stream(path.split("/", -1))
                .noneMatch(
                    segment -> segment.isEmpty() || ".".equals(segment) || "..".equals(segment));
    if (!valid) {
      throw new IllegalArgumentException(
          "files key \""
              + path
              + "\" must be /-separated segments, none empty, . or .., with no \\ and no control"
              + " character");
    }
  }
}

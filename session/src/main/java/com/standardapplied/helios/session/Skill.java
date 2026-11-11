/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Validate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * An Agent Skill (agentskills.io) a session offers its model, registered with {@link
 * SessionOptions.Builder#withSkills}: its name and description are listed in the session's system
 * prompt, and the built-in {@code Skill} tool returns its instructions and the list of its files,
 * or, once the skill is loaded, one file, when the model asks. A host builds it from anywhere: a
 * {@code SKILL.md} folder, a classpath resource or plain strings.
 *
 * <p>Build through {@link #newBuilder()}. The canonical record constructor is kept for record
 * semantics; callers should not call it directly, because its argument order will grow as the
 * record gains components.
 *
 * <p>A file's supplier is called each time a {@code Skill} call reads that file, never cached and
 * never called for an activation; a supplier that throws, or returns null, ends that call as {@code
 * ToolOutcome.Threw}. A skill shared by several sessions has its suppliers called concurrently, so
 * a supplier must be thread-safe.
 *
 * <p>A file path and the directory hold no {@code &}, {@code <}, {@code >} or {@code "} and no
 * control, format, line separator or paragraph separator character, so the tool lists each exactly
 * as a call must spell it, with nothing invisible. The name's 64-character bound keeps the opening
 * tag by which the session recognises a loaded skill within the half of a tool result any cut
 * keeps.
 *
 * @param name the skill's name, at most 64 lowercase letters and digits in groups joined by single
 *     hyphens ({@code [a-z0-9]+(-[a-z0-9]+)*})
 * @param description what the skill is for and when to use it, listed in the system prompt;
 *     non-blank
 * @param instructions the skill's instructions, the body of its {@code SKILL.md}; non-null, may be
 *     empty
 * @param directory where the skill's files live as a tool that runs them names it, such as a
 *     folder-backed skill's absolute path; empty for a skill that is not on disk, non-blank and
 *     without a refused character when present
 * @param files the skill's bundled files by relative path, each read only when a call asks for it;
 *     copied. Every key is {@code /}-separated segments, none empty, {@code .} or {@code ..}, with
 *     no {@code \} and no refused character
 */
public record Skill(
    String name,
    String description,
    String instructions,
    Optional<String> directory,
    Map<String, Supplier<String>> files) {

  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final int MAX_NAME_LENGTH = 64;
  private static final String REFUSED =
      "no &, <, >, \" and no control, format, line separator or paragraph separator character";

  /**
   * Canonical constructor; build through {@link #newBuilder()} instead.
   *
   * @throws NullPointerException if any argument, a key or a value of {@code files} is null
   * @throws IllegalArgumentException if {@code name} breaks the naming rule, {@code description} is
   *     blank, {@code directory} is present and blank or holds a refused character, or a key of
   *     {@code files} is no relative path
   */
  public Skill {
    Objects.requireNonNull(name, "name must not be null");
    if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "skill name \""
              + name
              + "\" must match "
              + NAME.pattern()
              + " in at most "
              + MAX_NAME_LENGTH
              + " characters");
    }
    Validate.notBlank("description", description);
    Objects.requireNonNull(instructions, "instructions must not be null");
    Objects.requireNonNull(directory, "directory must not be null");
    directory.ifPresent(Skill::requireValidDirectory);
    files = Map.copyOf(Objects.requireNonNull(files, "files must not be null"));
    files.keySet().forEach(Skill::requireRelativePath);
  }

  /**
   * Start building a skill.
   *
   * @return a builder with no name, description or instructions, no directory and no files
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Start building a skill from this one's components.
   *
   * @return a builder seeded with this skill's components
   */
  public Builder toBuilder() {
    var builder =
        newBuilder().withName(name).withDescription(description).withInstructions(instructions);
    directory.ifPresent(builder::withDirectory);
    return builder.withFiles(files);
  }

  private static void requireValidDirectory(String directory) {
    Validate.notBlank("directory", directory);
    if (!visible(directory)) {
      throw new IllegalArgumentException("directory \"" + directory + "\" must have " + REFUSED);
    }
  }

  private static void requireRelativePath(String path) {
    var valid =
        path.indexOf('\\') < 0
            && visible(path)
            && Arrays.stream(path.split("/", -1))
                .noneMatch(
                    segment -> segment.isEmpty() || ".".equals(segment) || "..".equals(segment));
    if (!valid) {
      throw new IllegalArgumentException(
          "files key \""
              + path
              + "\" must be /-separated segments, none empty, . or .., with no \\, "
              + REFUSED);
    }
  }

  /** Whether {@code text} holds no character the tool would have to escape or could not show. */
  private static boolean visible(String text) {
    return text.codePoints()
        .noneMatch(
            c ->
                "&<>\"".indexOf(c) >= 0
                    || Character.isISOControl(c)
                    || switch (Character.getType(c)) {
                      case Character.FORMAT,
                          Character.LINE_SEPARATOR,
                          Character.PARAGRAPH_SEPARATOR ->
                          true;
                      default -> false;
                    });
  }

  /**
   * Builds a {@link Skill}; not thread-safe, so confine one builder to a thread. Every rule is
   * checked by {@link #build()}.
   */
  public static final class Builder {

    private String name;
    private String description;
    private String instructions;
    private String directory;
    private final Map<String, Supplier<String>> files = new LinkedHashMap<>();

    private Builder() {}

    /**
     * Set the skill's name. Required.
     *
     * @param name the name, {@code [a-z0-9]+(-[a-z0-9]+)*} in at most 64 characters
     * @return this builder
     */
    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Set what the skill is for and when to use it, listed in the system prompt. Required.
     *
     * @param description the non-blank description
     * @return this builder
     */
    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    /**
     * Set the skill's instructions, the body of its {@code SKILL.md}. Required; may be empty.
     *
     * @param instructions the instructions
     * @return this builder
     */
    public Builder withInstructions(String instructions) {
      this.instructions = instructions;
      return this;
    }

    /**
     * Set where the skill's files live as a tool that runs them names it, such as a folder-backed
     * skill's absolute path. Unset by default, for a skill that is not on disk.
     *
     * @param directory the non-blank directory
     * @return this builder
     * @throws NullPointerException if {@code directory} is null
     */
    public Builder withDirectory(String directory) {
      this.directory = Objects.requireNonNull(directory, "directory must not be null");
      return this;
    }

    /**
     * Set the skill's files by relative path. Replaces any added before. Defaults to none.
     *
     * @param files each file's content by its path, read only when a call asks for that file
     * @return this builder
     * @throws NullPointerException if {@code files}, a key or a value is null
     */
    public Builder withFiles(Map<String, Supplier<String>> files) {
      var copy = Map.copyOf(Objects.requireNonNull(files, "files must not be null"));
      this.files.clear();
      this.files.putAll(copy);
      return this;
    }

    /**
     * Add one file, replacing one added before at the same path.
     *
     * @param path the file's relative path
     * @param content the file's content, read only when a call asks for this file
     * @return this builder
     * @throws NullPointerException if an argument is null
     */
    public Builder withFile(String path, Supplier<String> content) {
      files.put(
          Objects.requireNonNull(path, "path must not be null"),
          Objects.requireNonNull(content, "content must not be null"));
      return this;
    }

    /**
     * Build the skill.
     *
     * @return the skill
     * @throws NullPointerException if the name, the description or the instructions were never set
     * @throws IllegalArgumentException if a component breaks its rule
     */
    public Skill build() {
      return new Skill(name, description, instructions, Optional.ofNullable(directory), files);
    }
  }
}

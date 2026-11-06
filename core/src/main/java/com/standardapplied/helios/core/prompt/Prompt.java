/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.prompt;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.Strings;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An immutable, versioned prompt template.
 *
 * <p>Prompts use <code>{variable}</code> placeholders that are rendered via {@link
 * Strings#render(String, Map)}.
 *
 * <p>Variable names must consist of word characters only (letters, digits, and underscores). Names
 * containing hyphens, dots, or other special characters (e.g., <code>{user-name}</code> or <code>
 * {user.name}</code>) are not recognized as placeholders.
 *
 * @param id unique identifier
 * @param name the prompt name (e.g., "system", "greeting", "summarize")
 * @param content the template text with {variable} placeholders
 * @param version the version number (1-based, monotonically increasing per name)
 * @param active whether this is the active version for its name
 * @param variables the variable names extracted from the content, in order of first occurrence;
 *     non-null, stored as an unmodifiable copy in the given order, with no null name
 * @param createdAt when this version was created
 */
public record Prompt(
    UUID id,
    String name,
    String content,
    int version,
    boolean active,
    SequencedSet<String> variables,
    OffsetDateTime createdAt) {

  /**
   * Canonical constructor; copies {@code variables} into an unmodifiable set in the given order.
   * Every other component is stored as given, unvalidated.
   *
   * @throws NullPointerException if {@code variables} is {@code null} or holds a {@code null} name
   */
  public Prompt {
    Objects.requireNonNull(variables, "variables must not be null");
    variables.forEach(
        variable -> Objects.requireNonNull(variable, "variable name must not be null"));
    variables = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(variables));
  }

  private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{(\\w+)}");

  /**
   * Renders this prompt by substituting {placeholder} values.
   *
   * @param values the variable values to substitute
   * @return the rendered prompt text
   */
  public String render(Map<String, String> values) {
    return Strings.render(content, values);
  }

  /**
   * Extracts variable names from a template string. Variable names must match {@code \w+} (letters,
   * digits, underscores). Placeholders like {@code {user-name}} or {@code {user.name}} are not
   * recognized.
   *
   * @param content the template text with {variable} placeholders
   * @return the set of variable names found, in order of first occurrence
   */
  public static SequencedSet<String> extractVariables(String content) {
    var vars = new LinkedHashSet<String>();
    if (content != null) {
      var matcher = VARIABLE_PATTERN.matcher(content);
      while (matcher.find()) {
        vars.add(matcher.group(1));
      }
    }
    return Collections.unmodifiableSequencedSet(vars);
  }

  /**
   * Returns an empty builder: version 1, active, every other component unset.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Returns a builder holding every component of {@code prompt}, to derive a changed copy such as
   * the same version deactivated.
   *
   * @param prompt the prompt to copy; must not be {@code null}
   * @return a new builder seeded from {@code prompt}
   * @throws NullPointerException if {@code prompt} is {@code null}
   */
  public static Builder newBuilder(Prompt prompt) {
    return new Builder(prompt);
  }

  /** Builds a {@link Prompt}; not thread-safe, so confine one builder to a thread. */
  public static class Builder {
    private UUID id;
    private String name;
    private String content;
    private int version = 1;
    private boolean active = true;
    private SequencedSet<String> variables;
    private OffsetDateTime createdAt;

    private Builder() {}

    private Builder(Prompt prompt) {
      this.id = prompt.id;
      this.name = prompt.name;
      this.content = prompt.content;
      this.version = prompt.version;
      this.active = prompt.active;
      this.variables = prompt.variables;
      this.createdAt = prompt.createdAt;
    }

    /**
     * Sets the prompt's unique identifier. Unset or {@code null}, {@link #build()} generates a
     * time-ordered UUID.
     *
     * @param id the identifier, or {@code null} to generate one
     * @return this builder
     */
    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the name the prompt's versions share, such as {@code "system"}. Not validated here; a
     * {@link PromptRegistry} rejects a blank name.
     *
     * @param name the prompt name
     * @return this builder
     */
    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the template text, whose <code>{variable}</code> placeholders {@link #build()} extracts
     * unless {@link #withVariables(SequencedSet)} set them. Not validated here; a {@code null}
     * content yields no variables.
     *
     * @param content the template text
     * @return this builder
     */
    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    /**
     * Sets the version number, 1-based within the prompt's name. Defaults to 1; not validated.
     *
     * @param version the version number
     * @return this builder
     */
    public Builder withVersion(int version) {
      this.version = version;
      return this;
    }

    /**
     * Sets whether this version is the one its name resolves to. Defaults to {@code true}.
     *
     * @param active {@code true} for the active version, {@code false} for a draft or a retired one
     * @return this builder
     */
    public Builder withActive(boolean active) {
      this.active = active;
      return this;
    }

    /**
     * Sets the variable names explicitly instead of extracting them from the content. Unset or
     * {@code null}, {@link #build()} extracts them.
     *
     * @param variables the variable names in order, with no {@code null} element; or {@code null}
     *     to extract them
     * @return this builder
     */
    public Builder withVariables(SequencedSet<String> variables) {
      this.variables = variables;
      return this;
    }

    /**
     * Sets when this version was created. Unset or {@code null}, {@link #build()} uses the current
     * UTC time.
     *
     * @param createdAt the creation time, or {@code null} for now
     * @return this builder
     */
    public Builder withCreatedAt(OffsetDateTime createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    /**
     * Builds the Prompt. Auto-generates id and createdAt if not set. Auto-extracts variables from
     * content if not explicitly provided.
     *
     * @return a new prompt holding this builder's settings
     * @throws NullPointerException if the explicitly set variables hold a {@code null} name
     */
    public Prompt build() {
      if (id == null) {
        id = Ids.newId();
      }
      if (createdAt == null) {
        createdAt = Ids.now();
      }
      if (variables == null) {
        variables = Prompt.extractVariables(content);
      }
      return new Prompt(id, name, content, version, active, variables, createdAt);
    }
  }
}

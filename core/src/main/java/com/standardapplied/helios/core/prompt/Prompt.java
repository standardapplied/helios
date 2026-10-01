/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.prompt;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * @param variables the set of variable names extracted from the content
 * @param createdAt when this version was created
 */
public record Prompt(
    UUID id,
    String name,
    String content,
    int version,
    boolean active,
    Set<String> variables,
    OffsetDateTime createdAt) {

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
  public static Set<String> extractVariables(String content) {
    if (Strings.isBlank(content)) {
      return Set.of();
    }
    var matcher = VARIABLE_PATTERN.matcher(content);
    var vars = new LinkedHashSet<String>();
    while (matcher.find()) {
      vars.add(matcher.group(1));
    }
    return Set.copyOf(vars);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Prompt prompt) {
    return new Builder(prompt);
  }

  /** Builder for Prompt. */
  public static class Builder {
    private UUID id;
    private String name;
    private String content;
    private int version = 1;
    private boolean active = true;
    private Set<String> variables;
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

    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    public Builder withVersion(int version) {
      this.version = version;
      return this;
    }

    public Builder withActive(boolean active) {
      this.active = active;
      return this;
    }

    public Builder withVariables(Set<String> variables) {
      this.variables = variables;
      return this;
    }

    public Builder withCreatedAt(OffsetDateTime createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    /**
     * Builds the Prompt. Auto-generates id and createdAt if not set. Auto-extracts variables from
     * content if not explicitly provided.
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
      return new Prompt(id, name, content, version, active, Set.copyOf(variables), createdAt);
    }
  }
}

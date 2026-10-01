/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.prompt;

import ai.singlr.core.common.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory implementation of PromptRegistry. Thread-safe.
 *
 * <p>Useful for testing and applications that load prompts from configuration at startup.
 */
public class InMemoryPromptRegistry implements PromptRegistry {

  private final Map<String, List<Prompt>> prompts = new LinkedHashMap<>();

  @Override
  public synchronized Prompt register(String name, String content) {
    var versions = validateAndVersions(name, content);
    deactivateAll(versions);
    return append(versions, name, content, true);
  }

  @Override
  public synchronized Prompt registerDraft(String name, String content) {
    var versions = validateAndVersions(name, content);
    return append(versions, name, content, false);
  }

  @Override
  public synchronized Prompt activate(String name, int version) {
    var versions = prompts.get(name);
    if (versions == null || version < 1 || version > versions.size()) {
      throw new IllegalArgumentException("No prompt found: " + name + " v" + version);
    }
    deactivateAll(versions);
    var activated = Prompt.newBuilder(versions.get(version - 1)).withActive(true).build();
    versions.set(version - 1, activated);
    return activated;
  }

  @Override
  public synchronized Prompt resolve(String name) {
    var versions = prompts.get(name);
    if (versions == null) {
      return null;
    }
    for (var i = versions.size() - 1; i >= 0; i--) {
      var prompt = versions.get(i);
      if (prompt.active()) {
        return prompt;
      }
    }
    return null;
  }

  @Override
  public synchronized Prompt resolve(String name, int version) {
    var versions = prompts.get(name);
    if (versions == null || version < 1 || version > versions.size()) {
      return null;
    }
    return versions.get(version - 1);
  }

  @Override
  public synchronized List<Prompt> versions(String name) {
    var versions = prompts.get(name);
    if (versions == null) {
      return List.of();
    }
    return List.copyOf(versions);
  }

  private List<Prompt> validateAndVersions(String name, String content) {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Prompt name must not be blank");
    }
    if (content == null) {
      throw new IllegalArgumentException("Prompt content must not be null");
    }
    return prompts.computeIfAbsent(name, k -> new ArrayList<>());
  }

  private static void deactivateAll(List<Prompt> versions) {
    for (int i = 0; i < versions.size(); i++) {
      var p = versions.get(i);
      if (p.active()) {
        versions.set(i, Prompt.newBuilder(p).withActive(false).build());
      }
    }
  }

  private static Prompt append(List<Prompt> versions, String name, String content, boolean active) {
    var prompt =
        Prompt.newBuilder()
            .withName(name)
            .withContent(content)
            .withVersion(versions.size() + 1)
            .withActive(active)
            .build();
    versions.add(prompt);
    return prompt;
  }
}

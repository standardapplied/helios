/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - Skills Module.
 *
 * <p>Reads Agent Skills folders, each a {@code SKILL.md} with YAML frontmatter and a Markdown body,
 * into {@code helios-session} {@code Skill} values through {@code Skills.discover} and {@code
 * Skills.load}. It runs no skill code: a skill's scripts run through the session's {@code Execute}.
 */
module com.standardapplied.helios.skills {
  requires transitive com.standardapplied.helios.session;
  requires java.logging;
  requires tools.jackson.databind;
  requires tools.jackson.dataformat.yaml;

  exports com.standardapplied.helios.skills;
}

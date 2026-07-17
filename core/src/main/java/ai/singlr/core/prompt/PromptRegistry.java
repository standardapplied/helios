/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.prompt;

import java.util.List;

/**
 * Registry for versioned prompt templates.
 *
 * <p>Each prompt is identified by name and version. At most one version per name is active at a
 * time. Registering a new version via {@link #register} automatically activates it and deactivates
 * the previous active version; {@link #registerDraft} creates an inactive candidate that only goes
 * live through an explicit {@link #activate}.
 */
public interface PromptRegistry {

  /**
   * Registers a new prompt version. The version number is auto-assigned (previous max + 1). The new
   * version becomes active; the previous active version is deactivated.
   *
   * @param name the prompt name
   * @param content the template content with {variable} placeholders
   * @return the created prompt with version, id, and variables assigned
   */
  Prompt register(String name, String content);

  /**
   * Registers a new prompt version as an inactive draft. The version number is auto-assigned
   * (previous max + 1) exactly like {@link #register}, but the currently active version stays
   * active. Evaluate the draft by pinning it via {@link #resolve(String, int)}, then promote it
   * with {@link #activate}.
   *
   * @param name the prompt name
   * @param content the template content with {variable} placeholders
   * @return the created draft prompt with version, id, and variables assigned
   */
  Prompt registerDraft(String name, String content);

  /**
   * Makes the given version the active one for its name, deactivating whichever version was active
   * before. Activating an older version is the rollback path; activating the already-active version
   * is a no-op.
   *
   * @param name the prompt name
   * @param version the version number to activate
   * @return the activated prompt
   * @throws IllegalArgumentException if no prompt exists with that name and version
   */
  Prompt activate(String name, int version);

  /**
   * Resolves the active version of a prompt by name.
   *
   * @param name the prompt name
   * @return the active prompt, or null if no prompt exists with that name
   */
  Prompt resolve(String name);

  /**
   * Resolves a specific version of a prompt.
   *
   * @param name the prompt name
   * @param version the version number
   * @return the prompt at that version, or null if not found
   */
  Prompt resolve(String name, int version);

  /**
   * Lists all versions of a prompt, ordered by version ascending.
   *
   * @param name the prompt name
   * @return all versions, or an empty list if no prompt exists with that name
   */
  List<Prompt> versions(String name);
}

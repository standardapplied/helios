/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of running text through a {@link Redactor}.
 *
 * @param text the redacted text; never null
 * @param counts markers per secret name, in the order they appear; unmodifiable, empty if no secret
 *     matched
 */
public record RedactionResult(String text, Map<String, Integer> counts) {

  public RedactionResult {
    counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
  }
}

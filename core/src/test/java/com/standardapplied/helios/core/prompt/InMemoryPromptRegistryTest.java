/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.prompt;

import com.standardapplied.helios.core.test.PromptRegistryContract;

class InMemoryPromptRegistryTest extends PromptRegistryContract {

  @Override
  protected PromptRegistry createRegistry() {
    return new InMemoryPromptRegistry();
  }
}

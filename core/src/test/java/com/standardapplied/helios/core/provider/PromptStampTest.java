/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PromptStampTest {

  @Test
  void isTheLowercaseHexSha256OfThePrefix() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        PromptStamp.of("abc".getBytes(StandardCharsets.UTF_8)));
  }
}

/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every model's files come from one commit of its repository, which its spec names in full. */
class OnnxModelSpecTest {

  @ParameterizedTest
  @EnumSource(OnnxModelId.class)
  void everyCataloguedModelIsPinnedToACommit(OnnxModelId id) {
    var revision = OnnxModelSpec.lookup(id.id()).orElseThrow().revision();

    assertTrue(revision.matches("[0-9a-f]{40}"), revision);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "main",
        "e9b6763",
        "E9B6763023C676CA8431644204F50C2B100D9AAB",
        "e9b6763023c676ca8431644204f50c2b100d9aab0"
      })
  void aRevisionThatIsNotAFullCommitShaIsRefused(String revision) {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new OnnxModelSpec(
                    revision, OnnxModelSpec.ModelType.ENCODER, "onnx", "model", 8, 8, "", ""));

    assertEquals(
        "revision must be a 40-character lowercase hex commit SHA, got: " + revision,
        error.getMessage());
  }
}

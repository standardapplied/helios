/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnnxModelIdTest {

  @Test
  void fromIdFindsEveryCataloguedModel() {
    for (var model : OnnxModelId.values()) {
      assertEquals(model, OnnxModelId.fromId(model.id()));
    }
  }

  @Test
  void fromIdFindsNothingForAnUnknownOrBlankId() {
    assertNull(OnnxModelId.fromId("acme/tiny"));
    assertNull(OnnxModelId.fromId(" "));
    assertNull(OnnxModelId.fromId(null));
  }

  @Test
  void onlyACataloguedIdIsSupported() {
    assertTrue(OnnxModelId.isSupported("nomic-ai/nomic-embed-text-v1.5"));
    assertFalse(OnnxModelId.isSupported("acme/tiny"));
  }

  @Test
  void everyCataloguedModelHasASpec() {
    for (var model : OnnxModelId.values()) {
      assertTrue(OnnxModelSpec.isKnown(model.id()), model.id());
    }
  }
}

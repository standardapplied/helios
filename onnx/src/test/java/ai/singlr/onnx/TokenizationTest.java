/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.onnx;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.singlr.core.embedding.EmbeddingConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TokenizationTest {

  private static HuggingFaceTokenizer tokenizer;

  @BeforeAll
  static void setup() throws Exception {
    var config = EmbeddingConfig.defaults();
    var spec = OnnxModelSpec.lookup("nomic-ai/nomic-embed-text-v1.5").orElseThrow();
    var downloader = new OnnxModelDownloader("nomic-ai/nomic-embed-text-v1.5", config, spec);
    downloader.downloadModel();
    tokenizer = HuggingFaceTokenizer.newInstance(downloader.tokenizerPath());
  }

  @Test
  void tokenization() {
    var text = "This is a test document about machine learning";
    var encoding = tokenizer.encode(text);
    var tokens = encoding.getIds();

    assertNotNull(tokens, "Tokens should not be null");
    assertTrue(tokens.length > 0, "Should have at least one token");
    assertTrue(tokens.length < 50, "Short text should not have too many tokens");
  }

  @Test
  void attentionMask() {
    var text = "Hello world";
    var encoding = tokenizer.encode(text);
    var attentionMask = encoding.getAttentionMask();

    assertNotNull(attentionMask, "Attention mask should not be null");
    for (long mask : attentionMask) {
      assertTrue(mask == 0 || mask == 1, "Attention mask values should be 0 or 1");
    }
  }

  @Test
  void tokenTypeIds() {
    var text = "Test sentence";
    var encoding = tokenizer.encode(text);
    var tokenTypeIds = encoding.getTypeIds();
    assertNotNull(tokenTypeIds, "Token type IDs should not be null");
  }
}

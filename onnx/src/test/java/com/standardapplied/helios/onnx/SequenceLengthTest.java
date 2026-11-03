/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every known model reads its input up to its declared {@link OnnxModelSpec#sequenceLength()}: the
 * tokenizer truncates past it, keeping the special tokens, and warns once per truncated input.
 */
class SequenceLengthTest {

  private static final String SHORT_SENTENCE = "This is a test document about machine learning";
  private static final String PAST_512_TOKENS = "word ".repeat(600);
  private static final String PAST_GEMMA_LIMIT = "word ".repeat(3000);
  private static final String WARNING =
      "Text exceeds max sequence length (2048 tokens), truncated to fit";

  /** The special tokens a model's tokenizer puts before and after the text. */
  private record Template(long[] leading, long trailing) {

    static Template of(OnnxModelId id) {
      return switch (id) {
        case NOMIC_EMBED_V1_5 -> new Template(new long[] {101}, 102);
        case EMBEDDING_GEMMA_300M, HARRIER_OSS_V1_270M -> new Template(new long[] {2}, 1);
        case HARRIER_OSS_V1_0_6B -> new Template(new long[] {}, 151_643);
      };
    }
  }

  private static OnnxEmbeddingModel nomic;
  private static OnnxEmbeddingModel gemma;

  @BeforeAll
  static void open() {
    nomic = model(OnnxModelId.NOMIC_EMBED_V1_5);
    gemma = model(OnnxModelId.EMBEDDING_GEMMA_300M);
  }

  @AfterAll
  static void close() {
    nomic.close();
    gemma.close();
  }

  @ParameterizedTest
  @EnumSource(OnnxModelId.class)
  void theTokenizerTruncatesAtTheDeclaredLengthKeepingTheSpecialTokens(OnnxModelId id)
      throws Exception {
    var spec = OnnxModelSpec.lookup(id.id()).orElseThrow();
    var path = tokenizerPath(id, spec);
    try (var tokenizer = spec.tokenizer(path);
        var untouched = HuggingFaceTokenizer.newInstance(path)) {
      var longInput = tokenizer.encode("word ".repeat(spec.sequenceLength()));
      var shortInput = tokenizer.encode(SHORT_SENTENCE);
      var ids = longInput.getIds();
      var template = Template.of(id);

      assertEquals(spec.sequenceLength(), ids.length);
      assertArrayEquals(template.leading(), Arrays.copyOf(ids, template.leading().length));
      assertEquals(template.trailing(), ids[ids.length - 1]);
      assertTrue(longInput.exceedMaxLength());
      assertFalse(shortInput.exceedMaxLength());
      assertArrayEquals(untouched.encode(SHORT_SENTENCE).getIds(), shortInput.getIds());
    }
  }

  @Test
  void anEncoderReadsTokensPast512() {
    assertTailIsRead(nomic);
  }

  @Test
  void aDecoderReadsTokensPast512() {
    assertTailIsRead(gemma);
  }

  @Test
  void inputPastTheLimitIsTruncatedThereWithOneWarning() {
    var records = new ArrayList<LogRecord>();

    var truncated = captured(records, () -> unwrap(gemma.embed(PAST_GEMMA_LIMIT)));
    var longer = unwrap(gemma.embed(PAST_GEMMA_LIMIT + "a tail the model never reads"));

    assertEquals(List.of(WARNING), records.stream().map(LogRecord::getMessage).toList());
    assertArrayEquals(truncated, longer);
  }

  @Test
  void inputWithinTheLimitLogsNothing() {
    var records = new ArrayList<LogRecord>();

    captured(records, () -> unwrap(gemma.embed(PAST_512_TOKENS)));

    assertEquals(List.of(), records);
  }

  private static void assertTailIsRead(OnnxEmbeddingModel model) {
    var nearTheRiver = unwrap(model.embed(PAST_512_TOKENS + "near the river bank"));
    var inTheMeadow = unwrap(model.embed(PAST_512_TOKENS + "in a quiet meadow at dawn"));

    assertFalse(Arrays.equals(nearTheRiver, inTheMeadow));
  }

  private static float[] captured(List<LogRecord> records, Supplier<float[]> embedding) {
    var logger = Logger.getLogger(OnnxEmbeddingModel.class.getName());
    var handler =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            records.add(logRecord);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      return embedding.get();
    } finally {
      logger.removeHandler(handler);
    }
  }

  private static Path tokenizerPath(OnnxModelId id, OnnxModelSpec spec) throws Exception {
    try (var downloader = new OnnxModelDownloader(id.id(), EmbeddingConfig.defaults(), spec)) {
      return downloader.downloadModel().tokenizer();
    }
  }

  private static OnnxEmbeddingModel model(OnnxModelId id) {
    return OnnxEmbeddingModel.create(
        id.id(), EmbeddingConfig.defaults(), OnnxModelSpec.lookup(id.id()).orElseThrow());
  }

  private static float[] unwrap(Result<float[]> result) {
    return ((Result.Success<float[]>) assertInstanceOf(Result.Success.class, result)).value();
  }
}

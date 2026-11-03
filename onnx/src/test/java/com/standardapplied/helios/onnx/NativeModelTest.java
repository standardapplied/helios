/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the native resources of a model are released: a resource that fails to close, even with an
 * {@link Error}, is reported and the rest are still closed, an {@link Error} from closing a model
 * is thrown once everything was closed, and a model releases them on its first {@code close} only,
 * so its second {@code close} finds nothing to fail on.
 */
class NativeModelTest {

  private static final String NOMIC = OnnxModelId.NOMIC_EMBED_V1_5.id();
  private static final OnnxModelSpec SPEC = OnnxModelSpec.lookup(NOMIC).orElseThrow();

  private final Logger logger = Logger.getLogger(NativeModel.class.getName());
  private final Recorder recorder = new Recorder();

  @BeforeEach
  void record() {
    logger.addHandler(recorder);
  }

  @AfterEach
  void stopRecording() {
    logger.removeHandler(recorder);
  }

  @Test
  void aResourceThatFailsToCloseIsLoggedAndClosingGoesOn() throws Exception {
    var files = nomicFiles();
    var nativeModel = NativeModel.open(SPEC, files.tokenizer(), files.model());
    nativeModel.session().close();

    nativeModel.close();

    assertTrue(nativeModel.tokenizer().isReleased());
    assertThrows(IllegalStateException.class, nativeModel.options()::close);
    var failure = recorder.records.getFirst();
    assertEquals(1, recorder.records.size());
    assertEquals("Error closing an ONNX native resource", failure.getMessage());
    assertInstanceOf(IllegalStateException.class, failure.getThrown());
  }

  @Test
  void anErrorClosingAResourceIsHandedOnAndClosingGoesOn() {
    var original = new OrtException("could not create the session");
    var linkError = new UnsatisfiedLinkError("native library gone");
    var nextClosed = new AtomicBoolean();

    NativeModel.release(
        original::addSuppressed,
        () -> {
          throw linkError;
        },
        () -> nextClosed.set(true));

    assertArrayEquals(new Throwable[] {linkError}, original.getSuppressed());
    assertTrue(nextClosed.get());
  }

  @Test
  void anErrorClosingAModelIsThrownOnceEveryResourceIsClosed() {
    var linkError = new UnsatisfiedLinkError("native library gone");
    var laterError = new OutOfMemoryError("no room to free");
    var lastClosed = new AtomicBoolean();

    var thrown =
        assertThrows(
            UnsatisfiedLinkError.class,
            () ->
                NativeModel.closeAll(
                    () -> {
                      throw linkError;
                    },
                    () -> {
                      throw new IllegalStateException("already closed");
                    },
                    () -> {
                      throw laterError;
                    },
                    () -> lastClosed.set(true)));

    assertEquals(linkError, thrown);
    assertArrayEquals(new Throwable[] {laterError}, thrown.getSuppressed());
    assertTrue(lastClosed.get());
    assertEquals(1, recorder.records.size());
    assertInstanceOf(IllegalStateException.class, recorder.records.getFirst().getThrown());
  }

  @Test
  void aModelClosedTwiceReleasesItsResourcesOnce() {
    var model = OnnxEmbeddingModel.create(NOMIC, EmbeddingConfig.defaults(), SPEC);

    model.close();
    model.close();

    assertEquals(List.of(), recorder.records);
  }

  private static OnnxModelDownloader.ModelFiles nomicFiles() throws IOException {
    try (var downloader = new OnnxModelDownloader(NOMIC, EmbeddingConfig.defaults(), SPEC)) {
      return downloader.downloadModel();
    }
  }

  /** Keeps every warning the {@link NativeModel} logger publishes while a test runs. */
  private static final class Recorder extends Handler {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    private Recorder() {
      setLevel(Level.WARNING);
    }

    @Override
    public void publish(LogRecord logRecord) {
      if (isLoggable(logRecord)) {
        records.add(logRecord);
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}

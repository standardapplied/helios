/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hermetic tests for {@link OnnxModelDownloader#resolveLocalPath(Path, String)}. The HuggingFace
 * API {@code path} field flows verbatim into local filesystem writes; a hostile mirror or future
 * compromised model could return {@code "../../tmp/pwn"} to escape the model cache directory and
 * overwrite arbitrary JVM-writable files. {@code resolveLocalPath} is the jail.
 */
class OnnxModelDownloaderPathJailTest {

  @Test
  void plainFilenameStaysInsideRoot(@TempDir Path tmp) throws IOException {
    var resolved = OnnxModelDownloader.resolveLocalPath(tmp, "model.onnx");
    assertEquals(tmp.resolve("model.onnx").toAbsolutePath().normalize(), resolved);
  }

  @Test
  void parentTraversalIsRejected(@TempDir Path tmp) {
    assertThrows(
        IOException.class, () -> OnnxModelDownloader.resolveLocalPath(tmp, "../../etc/passwd"));
  }

  @Test
  void absolutePathIsRejected(@TempDir Path tmp) {
    assertThrows(IOException.class, () -> OnnxModelDownloader.resolveLocalPath(tmp, "/tmp/pwn"));
  }

  @Test
  void embeddedTraversalIsRejected(@TempDir Path tmp) {
    assertThrows(
        IOException.class,
        () -> OnnxModelDownloader.resolveLocalPath(tmp, "subdir/../../../escape"));
  }

  @Test
  void nestedSubdirectoryAccepted(@TempDir Path tmp) throws IOException {
    var resolved = OnnxModelDownloader.resolveLocalPath(tmp, "subdir/model.onnx_data");
    assertEquals(tmp.resolve("subdir/model.onnx_data").toAbsolutePath().normalize(), resolved);
  }
}

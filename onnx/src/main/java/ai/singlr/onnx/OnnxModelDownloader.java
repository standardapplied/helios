/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.onnx;

import ai.singlr.core.common.Strings;
import ai.singlr.core.embedding.EmbeddingConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Downloads ONNX models and tokenizer files from HuggingFace. Caches downloaded models locally and
 * skips re-download if a finished marker exists.
 */
final class OnnxModelDownloader implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(OnnxModelDownloader.class.getName());
  private static final String FINISHED_MARKER = ".finished";
  private static final String HF_API_BASE = "https://huggingface.co/api/models/";
  private static final String HF_DOWNLOAD_BASE = "https://huggingface.co/%s/resolve/main/%s";
  private static final String TOKENIZER_FILE = "tokenizer.json";

  private final String modelName;
  private final EmbeddingConfig config;
  private final OnnxModelSpec spec;
  private final HttpClient httpClient;
  private final Path localModelDir;

  OnnxModelDownloader(String modelName, EmbeddingConfig config, OnnxModelSpec spec) {
    this.modelName = modelName;
    this.config = config;
    this.spec = spec;
    this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    var parts = modelName.split("/");
    var owner = parts.length == 2 ? parts[0] : modelName;
    var shortName = parts.length == 2 ? parts[1] : modelName;
    this.localModelDir = Paths.get(config.workingDirectory(), owner, shortName);
  }

  void downloadModel() throws IOException {
    if (Files.exists(localModelDir.resolve(FINISHED_MARKER))) {
      LOGGER.info("Model already downloaded at: %s".formatted(localModelDir));
      return;
    }

    var subfolder = spec.onnxSubfolder();
    LOGGER.info("Downloading ONNX model: %s".formatted(modelName));

    var onnxFilesToDownload = new ArrayList<String>();
    var rootFilesToDownload = new ArrayList<String>();
    var hasOnnxModel = false;

    if (!Strings.isBlank(subfolder)) {
      var subfolderFiles = fetchFileList(modelName, "main/" + subfolder);
      for (var currFile : subfolderFiles) {
        if (isSelectedOnnxFile(currFile)) {
          if (currFile.toLowerCase().endsWith(".onnx")) {
            hasOnnxModel = true;
          }
          onnxFilesToDownload.add(currFile);
        }
      }

      var rootFiles = fetchFileList(modelName, "main");
      for (var currFile : rootFiles) {
        var f = currFile.toLowerCase();
        if (isTokenizerFile(f)) {
          rootFilesToDownload.add(currFile);
        }
      }
    } else {
      var allFiles = fetchFileList(modelName, "main");
      for (var currFile : allFiles) {
        var f = currFile.toLowerCase();
        if (isSelectedOnnxFile(currFile)) {
          if (f.endsWith(".onnx")) {
            hasOnnxModel = true;
          }
          onnxFilesToDownload.add(currFile);
        } else if (isTokenizerFile(f)) {
          rootFilesToDownload.add(currFile);
        }
      }
    }

    if (!hasOnnxModel) {
      throw new IOException("Model is not available in ONNX format");
    }

    Files.createDirectories(localModelDir);

    for (var currFile : onnxFilesToDownload) {
      var localFileName = stripSubfolderPrefix(currFile, subfolder);
      var destination = resolveLocalPath(localModelDir, localFileName);
      LOGGER.info("Downloading: %s -> %s".formatted(currFile, localFileName));
      downloadFile(modelName, currFile, destination);
    }

    for (var currFile : rootFilesToDownload) {
      var destination = resolveLocalPath(localModelDir, currFile);
      LOGGER.info("Downloading: %s".formatted(currFile));
      downloadFile(modelName, currFile, destination);
    }

    var marker = localModelDir.resolve(FINISHED_MARKER);
    if (!Files.exists(marker)) {
      try {
        Files.createFile(marker);
      } catch (FileAlreadyExistsException ignored) {
        // A concurrent downloader for the same model won the race. Both downloaded the same
        // content; the marker is a flag, not state, so either creator is fine.
      }
    }
    LOGGER.info("Model download completed: %s".formatted(localModelDir));
  }

  /**
   * Resolve a HuggingFace-API-supplied relative file path against the local model directory,
   * refusing anything that escapes the directory either lexically (via {@code ..} traversal) or by
   * supplying an absolute path. The HF API's {@code path} field flows verbatim from the network
   * into {@link Files#copy}; without this jail a compromised mirror or future malicious model card
   * could return {@code "../../tmp/pwn"} and overwrite any JVM-writable file. Visible for tests.
   *
   * @throws IOException when the requested path escapes the root or is absolute
   */
  static Path resolveLocalPath(Path root, String requested) throws IOException {
    var normalizedRoot = root.toAbsolutePath().normalize();
    var requestedPath = Paths.get(requested);
    if (requestedPath.isAbsolute()) {
      throw new IOException(
          "HuggingFace API returned an absolute path; refusing to escape model cache: "
              + requested);
    }
    var resolved = normalizedRoot.resolve(requestedPath).normalize();
    if (!resolved.startsWith(normalizedRoot)) {
      throw new IOException(
          "HuggingFace API returned a path that escapes the model cache directory: " + requested);
    }
    return resolved;
  }

  Path modelPath() {
    return localModelDir.resolve(spec.onnxFilenamePrefix() + ".onnx");
  }

  Path tokenizerPath() {
    return localModelDir.resolve(TOKENIZER_FILE);
  }

  private boolean isSelectedOnnxFile(String filePath) {
    var name = filePath;
    var slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    var prefix = spec.onnxFilenamePrefix();
    return name.equals(prefix + ".onnx")
        || name.equals(prefix + ".onnx_data")
        || name.startsWith(prefix + ".onnx_data_");
  }

  private List<String> fetchFileList(String hfModel, String treePath) throws IOException {
    var url = HF_API_BASE + hfModel + "/tree/" + treePath;
    var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOGGER.warning(
            "Failed to fetch file list from %s: %d".formatted(url, response.statusCode()));
        return List.of();
      }
      return parseFileList(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while fetching file list", e);
    }
  }

  private void downloadFile(String hfModel, String filePath, Path destination) throws IOException {
    if (Files.exists(destination) && Files.size(destination) > 0) {
      LOGGER.info("Skipping (already present): %s".formatted(destination.getFileName()));
      return;
    }
    var url = HF_DOWNLOAD_BASE.formatted(hfModel, filePath);
    var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new IOException(
            "Failed to download file %s: HTTP %d".formatted(url, response.statusCode()));
      }
      Files.createDirectories(destination.getParent());
      try (InputStream inputStream = response.body()) {
        Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while downloading file", e);
    }
  }

  private List<String> parseFileList(String modelInfo) throws IOException {
    var fileList = new ArrayList<String>();
    var objectMapper = new ObjectMapper();
    var siblingsNode = objectMapper.readTree(modelInfo);
    if (siblingsNode.isArray()) {
      for (JsonNode siblingNode : siblingsNode) {
        fileList.add(siblingNode.path("path").asText());
      }
    }
    return fileList;
  }

  private boolean isTokenizerFile(String filename) {
    return filename.contains("tokenizer")
        || filename.equals("config.json")
        || filename.equals("special_tokens_map.json")
        || filename.equals("vocab.txt");
  }

  private String stripSubfolderPrefix(String filePath, String subfolder) {
    if (!Strings.isBlank(subfolder) && filePath.startsWith(subfolder + "/")) {
      return filePath.substring(subfolder.length() + 1);
    }
    return filePath;
  }

  @Override
  public void close() {
    httpClient.shutdown();
    try {
      if (!httpClient.awaitTermination(Duration.ofSeconds(5))) {
        httpClient.shutdownNow();
      }
    } catch (InterruptedException e) {
      httpClient.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}

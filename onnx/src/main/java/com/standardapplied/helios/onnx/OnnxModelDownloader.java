/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.onnx;

import static java.util.function.Predicate.not;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.embedding.EmbeddingConfig;
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
  private static final URI HUGGING_FACE = URI.create("https://huggingface.co");
  private static final String TOKENIZER_FILE = "tokenizer.json";

  private final String modelName;
  private final OnnxModelSpec spec;
  private final HttpClient httpClient;
  private final Path localModelDir;
  private final URI hub;

  OnnxModelDownloader(String modelName, EmbeddingConfig config, OnnxModelSpec spec) {
    this(modelName, config, spec, HUGGING_FACE);
  }

  /** Downloads from {@code hub} instead of Hugging Face. Visible for tests. */
  OnnxModelDownloader(String modelName, EmbeddingConfig config, OnnxModelSpec spec, URI hub) {
    this.hub = hub;
    this.modelName = modelName;
    this.spec = spec;
    this.httpClient = HttpClientFactory.createForDownloads();

    var parts = modelName.split("/");
    var owner = parts.length == 2 ? parts[0] : modelName;
    var shortName = parts.length == 2 ? parts[1] : modelName;
    this.localModelDir = Paths.get(config.workingDirectory(), owner, shortName);
  }

  /**
   * Downloads the model unless a finished marker shows an earlier run completed it.
   *
   * @return the files the model is loaded from
   */
  ModelFiles downloadModel() throws IOException {
    var downloaded =
        new ModelFiles(
            localModelDir.resolve(spec.onnxFilenamePrefix() + ".onnx"),
            localModelDir.resolve(TOKENIZER_FILE));
    if (Files.exists(localModelDir.resolve(FINISHED_MARKER))) {
      LOGGER.info("Model already downloaded at: %s".formatted(localModelDir));
      return downloaded;
    }
    LOGGER.info("Downloading ONNX model: %s".formatted(modelName));
    var files = chooseFiles(listFiles());
    Files.createDirectories(localModelDir);
    for (var file : files) {
      var destination = resolveLocalPath(localModelDir, file.localName());
      LOGGER.info(file.progress());
      downloadFile(file.remotePath(), destination);
    }
    writeFinishedMarker();
    LOGGER.info("Model download completed: %s".formatted(localModelDir));
    return downloaded;
  }

  private Listing listFiles() throws IOException {
    var subfolder = spec.onnxSubfolder();
    if (Strings.isBlank(subfolder)) {
      var files = fetchFileList("main");
      return new Listing(files, files.stream().filter(not(this::isSelectedOnnxFile)).toList(), "");
    }
    return new Listing(fetchFileList("main/" + subfolder), fetchFileList("main"), subfolder + "/");
  }

  private List<ModelFile> chooseFiles(Listing listing) throws IOException {
    var onnxFiles = listing.onnxCandidates().stream().filter(this::isSelectedOnnxFile).toList();
    if (onnxFiles.stream().noneMatch(file -> file.toLowerCase().endsWith(".onnx"))) {
      throw new IOException("Model is not available in ONNX format");
    }
    var chosen = new ArrayList<ModelFile>();
    for (var file : onnxFiles) {
      var localName = listing.localName(file);
      chosen.add(
          new ModelFile(file, localName, "Downloading: %s -> %s".formatted(file, localName)));
    }
    for (var file : listing.rootCandidates()) {
      if (isTokenizerFile(file.toLowerCase())) {
        chosen.add(new ModelFile(file, file, "Downloading: %s".formatted(file)));
      }
    }
    return chosen;
  }

  private void writeFinishedMarker() throws IOException {
    var marker = localModelDir.resolve(FINISHED_MARKER);
    if (Files.exists(marker)) {
      return;
    }
    try {
      Files.createFile(marker);
    } catch (FileAlreadyExistsException ignored) {
      // A concurrent downloader for the same model won the race. Both downloaded the same
      // content; the marker is a flag, not state, so either creator is fine.
    }
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

  private List<String> fetchFileList(String treePath) throws IOException {
    var url = hub + "/api/models/" + modelName + "/tree/" + treePath;
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

  private void downloadFile(String filePath, Path destination) throws IOException {
    if (Files.exists(destination) && Files.size(destination) > 0) {
      LOGGER.info("Skipping (already present): %s".formatted(destination.getFileName()));
      return;
    }
    var url = "%s/%s/resolve/main/%s".formatted(hub, modelName, filePath);
    var request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        throw new IOException(
            "Failed to download file %s: HTTP %d".formatted(url, response.statusCode()));
      }
      try (InputStream inputStream = response.body()) {
        writeAtomically(inputStream, destination);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while downloading file", e);
    }
  }

  /**
   * Copies {@code content} to a temporary sibling of {@code destination} and renames it into place
   * only once the copy is complete, so an interrupted download never leaves a partial file where
   * the next run would take it for a finished one. Visible for tests.
   */
  static void writeAtomically(InputStream content, Path destination) throws IOException {
    var directory = destination.getParent();
    Files.createDirectories(directory);
    var partial = Files.createTempFile(directory, destination.getFileName().toString(), ".part");
    try {
      Files.copy(content, partial, StandardCopyOption.REPLACE_EXISTING);
      Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
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

  @Override
  public void close() {
    HttpClientFactory.shutdownGracefully(httpClient);
  }

  /**
   * The files a downloaded model is loaded from.
   *
   * @param model the ONNX model file
   * @param tokenizer the tokenizer file
   */
  record ModelFiles(Path model, Path tokenizer) {}

  /**
   * What the hub lists for a model: the candidates for its ONNX files, the candidates for its
   * tokenizer files, and the subfolder prefix an ONNX file's hub path loses in the model directory.
   */
  private record Listing(
      List<String> onnxCandidates, List<String> rootCandidates, String subfolderPrefix) {

    String localName(String onnxFile) {
      return onnxFile.startsWith(subfolderPrefix)
          ? onnxFile.substring(subfolderPrefix.length())
          : onnxFile;
    }
  }

  /**
   * A file chosen for download: its path on the hub, its name in the model directory and the
   * progress line logged when it is fetched.
   */
  private record ModelFile(String remotePath, String localName, String progress) {}
}

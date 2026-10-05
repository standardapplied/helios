/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import com.standardapplied.helios.core.test.StubHttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hermetic tests for {@link OnnxModelDownloader#downloadModel()} against a stub hub: which files it
 * lists, chooses, requests and writes, in which order, and the finished marker that ends a run.
 */
class OnnxModelDownloaderTest {

  private static final String MODEL = "acme/tiny";
  private static final String TREE = "/api/models/acme/tiny/tree/";
  private static final String RESOLVE = "/acme/tiny/resolve/main/";
  private static final OnnxModelSpec SUBFOLDER_SPEC =
      new OnnxModelSpec(OnnxModelSpec.ModelType.ENCODER, "onnx", "model", 512, 8, "", "");
  private static final OnnxModelSpec ROOT_SPEC =
      new OnnxModelSpec(OnnxModelSpec.ModelType.ENCODER, "", "model", 512, 8, "", "");

  @TempDir Path cache;

  @Test
  void subfolderLayoutDownloadsTheSelectedOnnxFilesThenTheTokenizerFiles() throws IOException {
    var hub = new Hub();
    hub.listing(
        "main/onnx",
        "onnx/model.onnx",
        "onnx/model.onnx_data",
        "onnx/model.onnx_data_1",
        "onnx/model_quantized.onnx",
        "onnx/model_fp16.onnx_data");
    hub.listing(
        "main",
        "onnx",
        ".gitattributes",
        "README.md",
        "config.json",
        "tokenizer.json",
        "Tokenizer_Config.json",
        "special_tokens_map.json",
        "vocab.txt",
        "model.safetensors");
    hub.redirect(RESOLVE + "onnx/model.onnx", "/cdn/model.onnx");
    hub.file("/cdn/model.onnx", "weights");

    var requests = hub.download(SUBFOLDER_SPEC, cache);

    assertEquals(
        List.of(
            "GET " + TREE + "main/onnx",
            "GET " + TREE + "main",
            "GET " + RESOLVE + "onnx/model.onnx",
            "GET /cdn/model.onnx",
            "GET " + RESOLVE + "onnx/model.onnx_data",
            "GET " + RESOLVE + "onnx/model.onnx_data_1",
            "GET " + RESOLVE + "config.json",
            "GET " + RESOLVE + "tokenizer.json",
            "GET " + RESOLVE + "Tokenizer_Config.json",
            "GET " + RESOLVE + "special_tokens_map.json",
            "GET " + RESOLVE + "vocab.txt"),
        requests);
    var modelDir = cache.resolve("acme/tiny");
    assertEquals(
        Set.of(
            ".finished",
            "model.onnx",
            "model.onnx_data",
            "model.onnx_data_1",
            "config.json",
            "tokenizer.json",
            "Tokenizer_Config.json",
            "special_tokens_map.json",
            "vocab.txt"),
        entriesOf(modelDir));
    assertEquals("weights", Files.readString(modelDir.resolve("model.onnx")));
    assertEquals(
        "body of " + RESOLVE + "onnx/model.onnx_data",
        Files.readString(modelDir.resolve("model.onnx_data")));
    assertEquals(
        "body of " + RESOLVE + "tokenizer.json",
        Files.readString(modelDir.resolve("tokenizer.json")));
    assertEquals(0L, Files.size(modelDir.resolve(".finished")));
  }

  @Test
  void rootLayoutChoosesOnnxAndTokenizerFilesFromOneListing() throws IOException {
    var hub = new Hub();
    hub.listing(
        "main",
        "config.json",
        "model.onnx",
        "tokenizer.json",
        "model.safetensors",
        "onnx/model.onnx",
        "model.onnx_data");

    var requests = hub.download(ROOT_SPEC, cache);

    assertEquals(
        List.of(
            "GET " + TREE + "main",
            "GET " + RESOLVE + "model.onnx",
            "GET " + RESOLVE + "onnx/model.onnx",
            "GET " + RESOLVE + "model.onnx_data",
            "GET " + RESOLVE + "config.json",
            "GET " + RESOLVE + "tokenizer.json"),
        requests);
    var modelDir = cache.resolve("acme/tiny");
    assertEquals(
        Set.of(
            ".finished", "config.json", "model.onnx", "model.onnx_data", "onnx", "tokenizer.json"),
        entriesOf(modelDir));
    assertEquals(
        "body of " + RESOLVE + "onnx/model.onnx",
        Files.readString(modelDir.resolve("onnx/model.onnx")));
  }

  @Test
  void modelWithoutAnOnnxFileFailsBeforeCreatingTheModelDirectory() {
    var hub = new Hub();
    hub.listing("main/onnx", "onnx/model.onnx_data", "onnx/model_quantized.onnx");
    hub.listing("main", "tokenizer.json");

    var error = assertThrows(IOException.class, () -> hub.download(SUBFOLDER_SPEC, cache));

    assertEquals("Model is not available in ONNX format", error.getMessage());
    assertEquals(List.of("GET " + TREE + "main/onnx", "GET " + TREE + "main"), hub.requests());
    assertFalse(Files.exists(cache.resolve("acme/tiny")));
  }

  @Test
  void anUnavailableListingCountsAsEmpty() {
    var hub = new Hub();
    hub.listing("main", "tokenizer.json");

    var error = assertThrows(IOException.class, () -> hub.download(SUBFOLDER_SPEC, cache));

    assertEquals("Model is not available in ONNX format", error.getMessage());
    assertEquals(List.of("GET " + TREE + "main/onnx", "GET " + TREE + "main"), hub.requests());
  }

  @Test
  void aFailedFileDownloadReportsItsUrlAndStatusAndWritesNoMarker() {
    var hub = new Hub();
    hub.listing("main/onnx", "onnx/model.onnx");
    hub.listing("main", "tokenizer.json");
    hub.status(RESOLVE + "tokenizer.json", 500);

    var error = assertThrows(IOException.class, () -> hub.download(SUBFOLDER_SPEC, cache));

    assertEquals(
        "Failed to download file " + hub.uri() + RESOLVE + "tokenizer.json: HTTP 500",
        error.getMessage());
    var modelDir = cache.resolve("acme/tiny");
    assertTrue(Files.exists(modelDir.resolve("model.onnx")));
    assertFalse(Files.exists(modelDir.resolve(".finished")));
  }

  @Test
  void aListedPathOutsideTheCacheFailsAfterTheFilesBeforeIt() {
    var hub = new Hub();
    hub.listing("main/onnx", "onnx/model.onnx");
    hub.listing("main", "config.json", "../tokenizer.json", "vocab.txt");

    var error = assertThrows(IOException.class, () -> hub.download(SUBFOLDER_SPEC, cache));

    assertEquals(
        "HuggingFace API returned a path that escapes the model cache directory:"
            + " ../tokenizer.json",
        error.getMessage());
    assertEquals(
        List.of(
            "GET " + TREE + "main/onnx",
            "GET " + TREE + "main",
            "GET " + RESOLVE + "onnx/model.onnx",
            "GET " + RESOLVE + "config.json"),
        hub.requests());
    assertFalse(Files.exists(cache.resolve("acme/tokenizer.json")));
    assertFalse(Files.exists(cache.resolve("acme/tiny/.finished")));
  }

  @Test
  void aFinishedMarkerSkipsTheHub() throws IOException {
    Files.createDirectories(cache.resolve("acme/tiny"));
    Files.createFile(cache.resolve("acme/tiny/.finished"));
    var hub = new Hub();

    assertEquals(List.of(), hub.download(SUBFOLDER_SPEC, cache));
  }

  @Test
  void aPresentFileIsKeptAndAnEmptyOneIsDownloadedAgain() throws IOException {
    var modelDir = Files.createDirectories(cache.resolve("acme/tiny"));
    Files.writeString(modelDir.resolve("model.onnx"), "kept");
    Files.createFile(modelDir.resolve("tokenizer.json"));
    var hub = new Hub();
    hub.listing("main/onnx", "onnx/model.onnx");
    hub.listing("main", "tokenizer.json");

    var requests = hub.download(SUBFOLDER_SPEC, cache);

    assertEquals(
        List.of(
            "GET " + TREE + "main/onnx",
            "GET " + TREE + "main",
            "GET " + RESOLVE + "tokenizer.json"),
        requests);
    assertEquals("kept", Files.readString(modelDir.resolve("model.onnx")));
    assertEquals(
        "body of " + RESOLVE + "tokenizer.json",
        Files.readString(modelDir.resolve("tokenizer.json")));
    assertTrue(Files.exists(modelDir.resolve(".finished")));
  }

  @Test
  void modelAndTokenizerPathsSitInTheModelDirectory() {
    var config = EmbeddingConfig.newBuilder().withWorkingDirectory(cache.toString()).build();
    try (var downloader = new OnnxModelDownloader(MODEL, config, SUBFOLDER_SPEC)) {
      assertEquals(cache.resolve("acme/tiny/model.onnx"), downloader.modelPath());
      assertEquals(cache.resolve("acme/tiny/tokenizer.json"), downloader.tokenizerPath());
    }
  }

  private static Set<String> entriesOf(Path directory) throws IOException {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries.map(entry -> entry.getFileName().toString()).collect(Collectors.toSet());
    }
  }

  /**
   * A stub Hugging Face: file listings under {@code /api/models}, and a body naming its own path
   * for any other {@code GET} unless a status or redirect is set for it.
   */
  private static final class Hub {

    private final Map<String, StubHttpServer.Reply> replies = new HashMap<>();
    private final StubHttpServer server =
        StubHttpServer.start(InetAddress.getLoopbackAddress(), 0, this::reply);

    void listing(String treePath, String... paths) {
      var body =
          Stream.of(paths)
              .map(path -> "{\"type\":\"file\",\"path\":\"" + path + "\"}")
              .collect(Collectors.joining(",", "[", "]"));
      replies.put(TREE + treePath, new StubHttpServer.Reply(200, Map.of(), body));
    }

    void redirect(String path, String location) {
      replies.put(path, new StubHttpServer.Reply(302, Map.of("Location", location), ""));
    }

    void file(String path, String body) {
      replies.put(path, new StubHttpServer.Reply(200, Map.of(), body));
    }

    void status(String path, int status) {
      replies.put(path, new StubHttpServer.Reply(status, Map.of(), ""));
    }

    List<String> download(OnnxModelSpec spec, Path cache) throws IOException {
      var config = EmbeddingConfig.newBuilder().withWorkingDirectory(cache.toString()).build();
      try (server;
          var downloader = new OnnxModelDownloader(MODEL, config, spec, server.uri())) {
        downloader.downloadModel();
      }
      return requests();
    }

    String uri() {
      return server.uri().toString();
    }

    List<String> requests() {
      return server.requests().stream()
          .map(request -> request.requestLine().replace(" HTTP/1.1", ""))
          .toList();
    }

    private StubHttpServer.Reply reply(StubHttpServer.Request request) {
      var path = request.requestLine().split(" ")[1];
      var fallback =
          path.startsWith(TREE)
              ? new StubHttpServer.Reply(404, Map.of(), "")
              : new StubHttpServer.Reply(200, Map.of(), "body of " + path);
      return replies.getOrDefault(path, fallback);
    }
  }
}

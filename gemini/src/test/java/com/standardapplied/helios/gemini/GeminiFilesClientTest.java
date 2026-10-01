/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.ModelConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class GeminiFilesClientTest {

  @TempDir Path tempDir;

  @Test
  void productionHttpClientNeverFollowsRedirects() {
    var config = ModelConfig.newBuilder().withApiKey("key").build();

    try (var http = GeminiFilesClient.createHttpClient(config)) {
      assertEquals(HttpClient.Redirect.NEVER, http.followRedirects());
    }
  }

  @Test
  void uploadStreamsFileAndWaitsUntilActive() throws Exception {
    var video = tempDir.resolve("sample.mp4");
    Files.write(video, new byte[] {1, 2, 3, 4});
    var http = new StubHttpClient();
    http.enqueue(
        200,
        Map.of("X-Goog-Upload-URL", List.of("https://api.example/upload/session-1?upload_id=abc")),
        "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/video-1\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://api.example/v1beta/files/video-1\",\"state\":\"PROCESSING\"}}");
    http.enqueue(
        200,
        Map.of(),
        "{\"name\":\"files/video-1\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://api.example/v1beta/files/video-1\",\"state\":\"ACTIVE\"}");

    var client = client(http, Duration.ofSeconds(5));
    var reference = client.upload(video, "video/mp4");

    assertEquals("https://api.example/v1beta/files/video-1", reference.uri());
    assertEquals("video/mp4", reference.mimeType());
    assertEquals(3, http.requests.size());

    var start = http.requests.get(0);
    assertEquals("https://api.example/upload/v1beta/files", start.uri().toString());
    assertEquals("POST", start.method());
    assertEquals("resumable", header(start, "X-Goog-Upload-Protocol"));
    assertEquals("start", header(start, "X-Goog-Upload-Command"));
    assertEquals("4", header(start, "X-Goog-Upload-Header-Content-Length"));
    assertEquals("video/mp4", header(start, "X-Goog-Upload-Header-Content-Type"));
    assertTrue(requestBody(start).contains("\"display_name\":\"sample.mp4\""));
    assertTrue(start.headers().firstValue("Api-Revision").isEmpty());
    assertEquals(Duration.ofSeconds(60), start.timeout().orElseThrow());

    var upload = http.requests.get(1);
    assertEquals("https://api.example/upload/session-1?upload_id=abc", upload.uri().toString());
    assertEquals(4, upload.bodyPublisher().orElseThrow().contentLength());
    assertEquals("0", header(upload, "X-Goog-Upload-Offset"));
    assertEquals("upload, finalize", header(upload, "X-Goog-Upload-Command"));
    assertEquals("g-key", header(upload, "x-goog-api-key"));
    assertTrue(upload.timeout().isEmpty());

    var poll = http.requests.get(2);
    assertEquals("GET", poll.method());
    assertEquals("https://api.example/v1beta/files/video-1", poll.uri().toString());
    assertEquals(Duration.ofSeconds(60), poll.timeout().orElseThrow());
  }

  @Test
  void activeUploadResponseDoesNotPoll() throws Exception {
    var video = tempDir.resolve("ready.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/session-2")), "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/video-2\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://api.example/v1beta/files/video-2\",\"state\":\"ACTIVE\"}}");

    client(http, Duration.ofSeconds(5)).upload(video, "video/mp4");

    assertEquals(2, http.requests.size());
  }

  @Test
  void managedUploadExposesReferenceAndDeletesOnlyByValidatedResourceName() throws Exception {
    var video = tempDir.resolve("managed.mp4");
    Files.write(video, new byte[] {1, 2, 3});
    var http = new StubHttpClient();
    http.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/managed")), "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/managed-1\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://provider-supplied.example/private/video\",\"state\":\"ACTIVE\"}}");
    http.enqueue(204, Map.of(), "");

    var managed = client(http, Duration.ofSeconds(5)).uploadManaged(video, "video/mp4");

    assertEquals("files/managed-1", managed.resourceName());
    assertEquals("https://provider-supplied.example/private/video", managed.reference().uri());
    assertEquals("video/mp4", managed.reference().mimeType());

    managed.delete();
    managed.delete();
    managed.close();

    assertEquals(3, http.requests.size());
    var delete = http.requests.getLast();
    assertEquals("DELETE", delete.method());
    assertEquals("https://api.example/v1beta/files/managed-1", delete.uri().toString());
    assertFalse(delete.uri().toString().contains("provider-supplied.example"));
  }

  @Test
  void managedDeleteTreatsAlreadyAbsentFileAsSuccess() throws Exception {
    var video = tempDir.resolve("absent.mp4");
    Files.write(video, new byte[] {1});
    var http = managedUpload(httpClient(), "files/already-absent", "https://files.example/private");
    http.enqueue(404, Map.of(), "{\"error\":{\"message\":\"not found\"}}");

    try (var managed = client(http, Duration.ofSeconds(5)).uploadManaged(video, "video/mp4")) {
      managed.delete();
    }

    assertEquals(3, http.requests.size());
  }

  @Test
  void managedDeleteErrorDoesNotRetainCredentialsOrFileUri() throws Exception {
    var video = tempDir.resolve("private.mp4");
    Files.write(video, new byte[] {1});
    var apiKey = "api-key-canary-never-retain";
    var fileUri = "https://files.example/file-uri-canary-never-retain";
    var http = managedUpload(httpClient(), "files/private-1", fileUri);
    http.enqueue(
        500, Map.of(), "{\"error\":{\"message\":\"failed " + apiKey + " " + fileUri + "\"}}");
    var config =
        ModelConfig.newBuilder().withApiKey(apiKey).withBaseUrl("https://api.example/v1").build();
    var managed = client(config, http, Duration.ofSeconds(5)).uploadManaged(video, "video/mp4");

    var error = assertThrows(GeminiException.class, managed::delete);

    assertEquals(500, error.statusCode());
    assertFalse(error.getMessage().contains(apiKey));
    assertFalse(error.getMessage().contains(fileUri));
    assertFalse(exceptionGraphContains(error, apiKey));
    assertFalse(exceptionGraphContains(error, fileUri));
  }

  @Test
  void managedDeleteCanRetryAfterFailureAndStopsAfterSuccess() throws Exception {
    var video = tempDir.resolve("retry-delete.mp4");
    Files.write(video, new byte[] {1});
    var http = managedUpload(httpClient(), "files/retry-delete", "https://files.example/retry");
    http.enqueue(503, Map.of(), "temporary");
    http.enqueue(204, Map.of(), "");
    var managed = client(http, Duration.ofSeconds(5)).uploadManaged(video, "video/mp4");

    assertThrows(GeminiException.class, managed::delete);
    managed.delete();
    managed.delete();

    assertEquals(4, http.requests.size());
  }

  @Test
  void managedDeleteNeverFollowsCrossOriginRedirect() throws Exception {
    var video = tempDir.resolve("redirect-delete.mp4");
    Files.write(video, new byte[] {1});
    var http =
        managedUpload(httpClient(), "files/redirect-delete", "https://files.example/private");
    http.enqueue(
        302, Map.of("location", List.of("https://attacker.example/collect")), "redirecting");
    var managed = client(http, Duration.ofSeconds(5)).uploadManaged(video, "video/mp4");

    var error = assertThrows(GeminiException.class, managed::delete);

    assertEquals(302, error.statusCode());
    assertEquals(3, http.requests.size());
    assertEquals(
        "https://api.example/v1beta/files/redirect-delete",
        http.requests.getLast().uri().toString());
  }

  @Test
  void managedUploadRejectsInvalidResourceNameBeforeItCanBeDeleted() throws Exception {
    var video = tempDir.resolve("invalid-name.mp4");
    Files.write(video, new byte[] {1});
    var http = managedUpload(httpClient(), "files/good/../../other", "https://files.example/x");

    var error =
        assertThrows(
            GeminiException.class,
            () -> client(http, Duration.ofSeconds(5)).uploadManaged(video, "video/mp4"));

    assertTrue(error.getMessage().contains("invalid file name"));
    assertEquals(2, http.requests.size());
  }

  @Test
  void failedProcessingSurfacesApiError() throws Exception {
    var video = tempDir.resolve("failed.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/session-3")), "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/video-3\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://api.example/v1beta/files/video-3\",\"state\":\"PROCESSING\"}}");
    http.enqueue(
        200,
        Map.of(),
        "{\"name\":\"files/video-3\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://api.example/v1beta/files/video-3\",\"state\":\"FAILED\","
            + "\"error\":{\"code\":13,\"message\":\"codec rejected\"}}");

    var error =
        assertThrows(
            GeminiException.class,
            () -> client(http, Duration.ofSeconds(5)).upload(video, "video/mp4"));

    assertTrue(error.getMessage().contains("codec rejected"));
  }

  @Test
  void rejectsMissingOrCrossOriginUploadUrl() throws Exception {
    var video = tempDir.resolve("unsafe.mp4");
    Files.write(video, new byte[] {1});
    var missing = new StubHttpClient();
    missing.enqueue(200, Map.of(), "");
    var crossOrigin = new StubHttpClient();
    crossOrigin.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://attacker.example/upload")), "");
    var invalid = new StubHttpClient();
    invalid.enqueue(200, Map.of("x-goog-upload-url", List.of("/relative/upload")), "");

    var missingError =
        assertThrows(
            GeminiException.class,
            () -> client(missing, Duration.ofSeconds(5)).upload(video, "video/mp4"));
    var originError =
        assertThrows(
            GeminiException.class,
            () -> client(crossOrigin, Duration.ofSeconds(5)).upload(video, "video/mp4"));
    var invalidError =
        assertThrows(
            GeminiException.class,
            () -> client(invalid, Duration.ofSeconds(5)).upload(video, "video/mp4"));

    assertTrue(missingError.getMessage().contains("upload URL"));
    assertTrue(originError.getMessage().contains("origin"));
    assertTrue(invalidError.getMessage().contains("invalid upload URL"));
    assertEquals(1, crossOrigin.requests.size());
  }

  @Test
  void rejectsInvalidFilesBeforeSending() throws Exception {
    var empty = tempDir.resolve("empty.mp4");
    Files.createFile(empty);
    var http = new StubHttpClient();
    var client = client(http, Duration.ofSeconds(5));

    assertThrows(IllegalArgumentException.class, () -> client.upload(tempDir, "video/mp4"));
    assertThrows(IllegalArgumentException.class, () -> client.upload(empty, "video/mp4"));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload(tempDir.resolve("missing.mp4"), "video/mp4"));
    assertThrows(IllegalArgumentException.class, () -> client.upload(empty, "invalid"));
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void rejectsFilesAboveCurrentTwoGigabyteProviderLimitBeforeSending() throws Exception {
    var oversized = tempDir.resolve("oversized.mp4");
    try (var channel =
        Files.newByteChannel(oversized, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      channel.position(2L * 1024 * 1024 * 1024);
      channel.write(ByteBuffer.wrap(new byte[] {1}));
    }
    var http = new StubHttpClient();

    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> client(http, Duration.ofSeconds(5)).upload(oversized, "video/mp4"));

    assertTrue(error.getMessage().contains("2 GB"));
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void nonSuccessResponseIsBoundedAndCarriesStatus() throws Exception {
    var video = tempDir.resolve("rate-limited.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(429, Map.of(), "quota exceeded");

    var error =
        assertThrows(
            GeminiException.class,
            () -> client(http, Duration.ofSeconds(5)).upload(video, "video/mp4"));

    assertEquals(429, error.statusCode());
    assertTrue(error.getMessage().contains("quota exceeded"));
    assertTrue(error.isRetryable());
  }

  @Test
  void processingTimeoutStopsPolling() throws Exception {
    var video = tempDir.resolve("slow.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/session-4")), "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/video-4\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://api.example/v1beta/files/video-4\",\"state\":\"PROCESSING\"}}");

    var error =
        assertThrows(
            GeminiException.class,
            () -> client(http, Duration.ofSeconds(5)).upload(video, "video/mp4", Duration.ZERO));

    assertTrue(error.getMessage().contains("timed out"));
    assertEquals(2, http.requests.size());
  }

  @Test
  void rejectsMalformedAndIncompleteApiResponses() throws Exception {
    var video = tempDir.resolve("bad-response.mp4");
    Files.write(video, new byte[] {1});
    var malformed = uploadClient("not json");
    var missingFile = uploadClient("{}");
    var unknownState =
        uploadClient(
            "{\"file\":{\"name\":\"files/a\",\"mimeType\":\"video/mp4\","
                + "\"uri\":\"https://api.example/v1beta/files/a\",\"state\":\"PAUSED\"}}");
    var incompleteActive =
        uploadClient(
            "{\"file\":{\"name\":\"files/a\",\"mimeType\":\"video/mp4\","
                + "\"state\":\"ACTIVE\"}}");
    var invalidName =
        uploadClient(
            "{\"file\":{\"name\":\"../secrets\",\"mimeType\":\"video/mp4\","
                + "\"uri\":\"https://api.example/file\",\"state\":\"PROCESSING\"}}");

    assertTrue(uploadError(malformed, video).getMessage().contains("parse"));
    assertTrue(uploadError(missingFile, video).getMessage().contains("file resource"));
    assertTrue(uploadError(unknownState, video).getMessage().contains("unknown"));
    assertTrue(uploadError(incompleteActive, video).getMessage().contains("URI"));
    assertTrue(uploadError(invalidName, video).getMessage().contains("file name"));
  }

  @Test
  void constructorValidatesConfigurationBeforeCreatingRequests() {
    assertThrows(IllegalArgumentException.class, () -> new GeminiFilesClient(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GeminiFilesClient(ModelConfig.newBuilder().build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GeminiFilesClient(
                ModelConfig.newBuilder()
                    .withBaseUrl("file:///tmp/gemini")
                    .withHeader("authorization", "test")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GeminiFilesClient(
                ModelConfig.newBuilder()
                    .withBaseUrl("ftp://api.example/v1")
                    .withHeader("authorization", "test")
                    .build()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GeminiFilesClient(
                ModelConfig.newBuilder()
                    .withBaseUrl("https://api.example/v1?tenant=bad")
                    .withHeader("authorization", "test")
                    .build()));
  }

  @Test
  void inferredMimeTypeUsesDefaultFilesEndpoint() throws Exception {
    var video = tempDir.resolve("inferred.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(
        200,
        Map.of(
            "x-goog-upload-url",
            List.of("https://generativelanguage.googleapis.com/upload/session")),
        "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/inferred\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"https://generativelanguage.googleapis.com/v1beta/files/inferred\","
            + "\"state\":\"ACTIVE\"}}");
    var config = ModelConfig.newBuilder().withApiKey("g-key").build();

    var reference = client(config, http, Duration.ofSeconds(5)).upload(video);

    assertEquals("video/mp4", reference.mimeType());
    assertEquals(
        "https://generativelanguage.googleapis.com/upload/v1beta/files",
        http.requests.getFirst().uri().toString());
  }

  @Test
  void inferredMimeTypeFailsClearlyWhenUnknown() throws Exception {
    var file = tempDir.resolve("unknown.helios-unknown-media");
    Files.write(file, new byte[] {1});
    var http = new StubHttpClient();

    var error =
        assertThrows(
            IllegalArgumentException.class, () -> client(http, Duration.ofSeconds(5)).upload(file));

    assertTrue(error.getMessage().contains("call upload(path, mimeType)"));
    assertTrue(http.requests.isEmpty());
  }

  @Test
  void transportFailuresPreserveCauseAndInterruptStatus() throws Exception {
    var video = tempDir.resolve("transport.mp4");
    Files.write(video, new byte[] {1});
    var ioHttp = new StubHttpClient();
    ioHttp.sendFailure = new IOException("network down");

    var ioError =
        assertThrows(
            GeminiException.class,
            () -> client(ioHttp, Duration.ofSeconds(5)).upload(video, "video/mp4"));

    assertEquals("network down", ioError.getCause().getMessage());

    var interruptedHttp = new StubHttpClient();
    interruptedHttp.interruptOnSend = true;
    try {
      var interrupted =
          assertThrows(
              GeminiException.class,
              () -> client(interruptedHttp, Duration.ofSeconds(5)).upload(video, "video/mp4"));

      assertTrue(interrupted.getMessage().contains("interrupted"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void oversizedResponsesAndDurationsFailFast() throws Exception {
    var video = tempDir.resolve("oversized.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(
        200,
        Map.of("x-goog-upload-url", List.of("https://api.example/upload/session")),
        "x".repeat(1024 * 1024 + 1));
    var client = client(http, Duration.ofSeconds(5));

    var responseError =
        assertThrows(GeminiException.class, () -> client.upload(video, "video/mp4"));

    assertTrue(responseError.getMessage().contains("exceeded 1 MB"));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload(video, "video/mp4", Duration.ofSeconds(Long.MAX_VALUE)));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload(video, "video/mp4", Duration.ofSeconds(-1)));
    assertThrows(NullPointerException.class, () -> client.upload(video, "video/mp4", null));

    var jsonHttp = new StubHttpClient();
    jsonHttp.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/session")), "");
    jsonHttp.enqueue(200, Map.of(), "x".repeat(1024 * 1024 + 1));

    var jsonError =
        assertThrows(
            GeminiException.class,
            () -> client(jsonHttp, Duration.ofSeconds(5)).upload(video, "video/mp4"));

    assertTrue(jsonError.getMessage().contains("JSON response exceeded 1 MB"));
  }

  @Test
  void closeSupportsOwnedAndInjectedHttpClients() {
    client(new StubHttpClient(), Duration.ofSeconds(5)).close();

    var config =
        ModelConfig.newBuilder()
            .withBaseUrl("https://api.example/v1")
            .withHeader("authorization", "test")
            .build();
    new GeminiFilesClient(config).close();
  }

  @Test
  void customProxyPathPortHeadersAndTimeoutArePreserved() throws Exception {
    var video = tempDir.resolve("proxy.mp4");
    Files.write(video, new byte[] {1});
    var http = new StubHttpClient();
    http.enqueue(
        200,
        Map.of("x-goog-upload-url", List.of("http://api.example:8080/proxy/upload/session")),
        "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\"files/proxy\",\"mimeType\":\"video/mp4\","
            + "\"uri\":\"http://api.example:8080/proxy/v1beta/files/proxy\","
            + "\"state\":\"ACTIVE\"}}");
    var config =
        ModelConfig.newBuilder()
            .withBaseUrl("http://api.example:8080/proxy/v1/")
            .withHeader("authorization", "Bearer proxy-token")
            .withResponseTimeout(null)
            .build();

    client(config, http, Duration.ofSeconds(5)).upload(video, "video/mp4");

    var start = http.requests.getFirst();
    assertEquals("http://api.example:8080/proxy/upload/v1beta/files", start.uri().toString());
    assertEquals("Bearer proxy-token", header(start, "authorization"));
    assertTrue(start.headers().firstValue("x-goog-api-key").isEmpty());
    assertTrue(start.timeout().isEmpty());
  }

  private GeminiException uploadError(StubHttpClient http, Path video) {
    return assertThrows(
        GeminiException.class,
        () -> client(http, Duration.ofSeconds(5)).upload(video, "video/mp4"));
  }

  private static StubHttpClient httpClient() {
    return new StubHttpClient();
  }

  private static StubHttpClient managedUpload(
      StubHttpClient http, String resourceName, String fileUri) {
    http.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/managed")), "");
    http.enqueue(
        200,
        Map.of(),
        "{\"file\":{\"name\":\""
            + resourceName
            + "\",\"mimeType\":\"video/mp4\",\"uri\":\""
            + fileUri
            + "\",\"state\":\"ACTIVE\"}}");
    return http;
  }

  private static boolean exceptionGraphContains(Throwable error, String canary) {
    for (var current = error; current != null; current = current.getCause()) {
      if (String.valueOf(current.getMessage()).contains(canary)) {
        return true;
      }
    }
    return false;
  }

  private StubHttpClient uploadClient(String uploadResponse) {
    var http = new StubHttpClient();
    http.enqueue(
        200, Map.of("x-goog-upload-url", List.of("https://api.example/upload/session")), "");
    http.enqueue(200, Map.of(), uploadResponse);
    return http;
  }

  private GeminiFilesClient client(StubHttpClient http, Duration processingTimeout) {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("g-key")
            .withBaseUrl("https://api.example/v1")
            .withHeader("x-trace", "trace-1")
            .build();
    return client(config, http, processingTimeout);
  }

  private GeminiFilesClient client(
      ModelConfig config, StubHttpClient http, Duration processingTimeout) {
    var mapper =
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    return new GeminiFilesClient(config, http, mapper, Duration.ZERO, processingTimeout, false);
  }

  private static String header(HttpRequest request, String name) {
    return request.headers().firstValue(name).orElseThrow();
  }

  private static String requestBody(HttpRequest request) throws Exception {
    var output = new ByteArrayOutputStream();
    var done = new CompletableFuture<Void>();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
              }

              @Override
              public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
              }

              @Override
              public void onComplete() {
                done.complete(null);
              }
            });
    done.get(5, TimeUnit.SECONDS);
    return output.toString(StandardCharsets.UTF_8);
  }

  private static final class StubHttpClient extends HttpClient {
    private final Deque<StubResponse> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private IOException sendFailure;
    private boolean interruptOnSend;

    void enqueue(int status, Map<String, List<String>> headers, String body) {
      responses.addLast(new StubResponse(status, headers, body));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      requests.add(request);
      if (sendFailure != null) {
        throw sendFailure;
      }
      if (interruptOnSend) {
        throw new InterruptedException("interrupted by test");
      }
      if (responses.isEmpty()) {
        throw new IOException("No stub response queued");
      }
      return (HttpResponse<T>) responses.removeFirst().toResponse(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.of(Duration.ofSeconds(1));
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_2;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }

  private record StubResponse(int status, Map<String, List<String>> headers, String body) {
    HttpResponse<InputStream> toResponse(HttpRequest request) {
      return new HttpResponse<>() {
        @Override
        public int statusCode() {
          return status;
        }

        @Override
        public HttpHeaders headers() {
          return HttpHeaders.of(headers, (name, value) -> true);
        }

        @Override
        public InputStream body() {
          return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
          return Optional.empty();
        }

        @Override
        public HttpRequest request() {
          return request;
        }

        @Override
        public Optional<SSLSession> sslSession() {
          return Optional.empty();
        }

        @Override
        public URI uri() {
          return request.uri();
        }

        @Override
        public HttpClient.Version version() {
          return HttpClient.Version.HTTP_2;
        }
      };
    }
  }
}

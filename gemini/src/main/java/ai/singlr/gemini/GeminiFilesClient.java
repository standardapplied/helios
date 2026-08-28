/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

import ai.singlr.core.common.HttpClientFactory;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.FileReference;
import ai.singlr.core.model.ModelConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Client for streaming files to the Gemini Files API and waiting for processing to complete. */
public final class GeminiFilesClient implements AutoCloseable {

  private static final URI DEFAULT_API_ROOT =
      URI.create("https://generativelanguage.googleapis.com");
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
  private static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofMinutes(10);
  private static final long MAX_FILE_BYTES = 20L * 1024 * 1024 * 1024;
  private static final int MAX_JSON_BYTES = 1024 * 1024;
  private static final Pattern FILE_NAME =
      Pattern.compile("files/[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
  private static final Pattern MIME_TYPE =
      Pattern.compile("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+");

  private final ModelConfig config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI apiRoot;
  private final Duration pollInterval;
  private final Duration processingTimeout;
  private final boolean ownsHttpClient;

  /**
   * Creates a Files API client using the authentication, endpoint, headers, and connection settings
   * from the supplied model configuration.
   *
   * @param config Gemini model configuration
   * @throws IllegalArgumentException if the configuration cannot address an authenticated Gemini
   *     endpoint
   */
  public GeminiFilesClient(ModelConfig config) {
    this(
        config,
        createHttpClient(config),
        JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build(),
        DEFAULT_POLL_INTERVAL,
        DEFAULT_PROCESSING_TIMEOUT,
        true);
  }

  GeminiFilesClient(
      ModelConfig config,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      Duration pollInterval,
      Duration processingTimeout,
      boolean ownsHttpClient) {
    this.config = requireConfig(config);
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    this.pollInterval = requireNonNegative(pollInterval, "pollInterval");
    this.processingTimeout = requireNonNegative(processingTimeout, "processingTimeout");
    this.apiRoot = apiRoot(this.config);
    this.ownsHttpClient = ownsHttpClient;
  }

  /**
   * Streams a file to Gemini and waits until it is ready for use in an interaction.
   *
   * @param path readable, non-empty file of at most 20 GB
   * @return the provider file reference for a model message
   * @throws IllegalArgumentException if the file is invalid or its MIME type cannot be detected
   * @throws GeminiException if upload or processing fails
   */
  public FileReference upload(Path path) {
    Objects.requireNonNull(path, "path must not be null");
    String mimeType;
    try {
      mimeType = Files.probeContentType(path);
    } catch (IOException e) {
      throw new GeminiException("Failed to determine MIME type for " + path, e);
    }
    if (Strings.isBlank(mimeType)) {
      throw new IllegalArgumentException(
          "Could not determine MIME type for " + path + "; call upload(path, mimeType)");
    }
    return upload(path, mimeType);
  }

  /**
   * Streams a file with an explicit MIME type and waits for Gemini's processing to finish.
   *
   * @param path readable, non-empty file of at most 20 GB
   * @param mimeType valid media type such as {@code video/mp4}
   * @return the provider file reference for a model message
   * @throws IllegalArgumentException if the file or MIME type is invalid
   * @throws GeminiException if upload or processing fails
   */
  public FileReference upload(Path path, String mimeType) {
    return upload(path, mimeType, processingTimeout);
  }

  /**
   * Streams a file and waits up to the supplied duration for provider-side processing. The timeout
   * starts after the streamed upload completes.
   *
   * @param path readable, non-empty file of at most 20 GB
   * @param mimeType valid media type such as {@code video/mp4}
   * @param timeout maximum provider-side processing wait
   * @return the provider file reference for a model message
   * @throws IllegalArgumentException if an input is invalid
   * @throws GeminiException if upload or processing fails
   */
  public FileReference upload(Path path, String mimeType, Duration timeout) {
    var file = validateFile(path, mimeType);
    var validatedTimeout = requireNonNegative(timeout, "timeout");
    try {
      var uploadUri = startUpload(file);
      var uploaded = uploadBytes(uploadUri, file);
      return awaitActive(uploaded, validatedTimeout);
    } catch (GeminiException e) {
      throw e;
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with the Gemini Files API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("File upload interrupted", e);
    }
  }

  @Override
  public void close() {
    if (ownsHttpClient) {
      HttpClientFactory.shutdownGracefully(httpClient);
    }
  }

  private UploadFile validateFile(Path path, String mimeType) {
    Objects.requireNonNull(path, "path must not be null");
    if (!MIME_TYPE
        .matcher(Objects.requireNonNull(mimeType, "mimeType must not be null"))
        .matches()) {
      throw new IllegalArgumentException("mimeType must be a valid media type");
    }
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IllegalArgumentException("path must be a readable regular file: " + path);
    }
    try {
      var size = Files.size(path);
      if (size == 0) {
        throw new IllegalArgumentException("file must not be empty: " + path);
      }
      if (size > MAX_FILE_BYTES) {
        throw new IllegalArgumentException(
            "file exceeds the Gemini Files API 20 GB limit: " + path);
      }
      var displayName = path.getFileName().toString();
      if (displayName.length() > 512) {
        throw new IllegalArgumentException(
            "file name exceeds the Gemini Files API 512-character limit");
      }
      return new UploadFile(path, displayName, mimeType, size);
    } catch (IOException e) {
      throw new GeminiException("Failed to inspect file " + path, e);
    }
  }

  private URI startUpload(UploadFile file) throws IOException, InterruptedException {
    var body = serialize(Map.of("file", Map.of("display_name", file.displayName())));
    var defaults = authenticatedHeaders();
    defaults.put("Content-Type", "application/json");
    defaults.put("X-Goog-Upload-Protocol", "resumable");
    defaults.put("X-Goog-Upload-Command", "start");
    defaults.put("X-Goog-Upload-Header-Content-Length", Long.toString(file.size()));
    defaults.put("X-Goog-Upload-Header-Content-Type", file.mimeType());
    var request =
        requestBuilder(endpoint("/upload/v1beta/files"), defaults)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = send(request);
    closeSuccessBody(response);
    var value = response.headers().firstValue("x-goog-upload-url").orElse(null);
    if (Strings.isBlank(value)) {
      throw new GeminiException("Gemini Files API did not return an upload URL");
    }
    URI uploadUri;
    try {
      uploadUri = parseAbsoluteUri(value, "Gemini Files API returned an invalid upload URL");
    } catch (IllegalArgumentException e) {
      throw new GeminiException("Gemini Files API returned an invalid upload URL", e);
    }
    if (!sameOrigin(apiRoot, uploadUri)) {
      throw new GeminiException("Gemini Files API returned an upload URL on a different origin");
    }
    return uploadUri;
  }

  private GeminiFileResource uploadBytes(URI uploadUri, UploadFile file)
      throws IOException, InterruptedException {
    var defaults = authenticatedHeaders();
    defaults.put("Content-Type", file.mimeType());
    defaults.put("X-Goog-Upload-Offset", "0");
    defaults.put("X-Goog-Upload-Command", "upload, finalize");
    var request =
        requestBuilder(uploadUri, defaults, false)
            .POST(HttpRequest.BodyPublishers.ofFile(file.path()))
            .build();
    var response = send(request);
    var uploadResponse = readJson(response, GeminiFileUploadResponse.class);
    if (uploadResponse.file() == null) {
      throw new GeminiException("Gemini Files API upload response did not contain a file resource");
    }
    return uploadResponse.file();
  }

  private FileReference awaitActive(GeminiFileResource initial, Duration timeout)
      throws IOException, InterruptedException {
    var file = initial;
    var started = System.nanoTime();
    var timeoutNanos = timeout.toNanos();
    while (true) {
      var state = file.state();
      if ("ACTIVE".equals(state)) {
        if (Strings.isBlank(file.uri()) || Strings.isBlank(file.mimeType())) {
          throw new GeminiException("Active Gemini file is missing its URI or MIME type");
        }
        return FileReference.of(file.uri(), file.mimeType());
      }
      if ("FAILED".equals(state)) {
        var detail =
            file.error() != null && !Strings.isBlank(file.error().message())
                ? ": " + file.error().message()
                : "";
        throw new GeminiException("Gemini file processing failed" + detail);
      }
      if (state != null && !"PROCESSING".equals(state) && !"STATE_UNSPECIFIED".equals(state)) {
        throw new GeminiException("Gemini file entered unknown processing state: " + state);
      }
      if (System.nanoTime() - started >= timeoutNanos) {
        throw new GeminiException(
            "Gemini file processing timed out after " + timeout.toSeconds() + " seconds");
      }
      if (!pollInterval.isZero()) {
        Thread.sleep(pollInterval);
      }
      file = getFile(file.name());
    }
  }

  private GeminiFileResource getFile(String name) throws IOException, InterruptedException {
    if (Strings.isBlank(name) || !FILE_NAME.matcher(name).matches()) {
      throw new GeminiException("Gemini Files API returned an invalid file name");
    }
    var request = requestBuilder(endpoint("/v1beta/" + name), authenticatedHeaders()).GET().build();
    return readJson(send(request), GeminiFileResource.class);
  }

  private HttpResponse<InputStream> send(HttpRequest request)
      throws IOException, InterruptedException {
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (var body = response.body()) {
        var errorBody = HttpClientFactory.readBoundedErrorBody(body);
        throw new GeminiException(
            "Files API error (status " + response.statusCode() + "): " + errorBody,
            response.statusCode());
      }
    }
    return response;
  }

  private <T> T readJson(HttpResponse<InputStream> response, Class<T> type) throws IOException {
    try (var body = response.body()) {
      var bytes = body.readNBytes(MAX_JSON_BYTES + 1);
      if (bytes.length > MAX_JSON_BYTES) {
        throw new GeminiException("Gemini Files API JSON response exceeded 1 MB");
      }
      try {
        return objectMapper.readValue(bytes, type);
      } catch (Exception e) {
        throw new GeminiException("Failed to parse Gemini Files API response", e);
      }
    }
  }

  private void closeSuccessBody(HttpResponse<InputStream> response) throws IOException {
    try (var body = response.body()) {
      if (body.readNBytes(MAX_JSON_BYTES + 1).length > MAX_JSON_BYTES) {
        throw new GeminiException("Gemini Files API response exceeded 1 MB");
      }
    }
  }

  private HttpRequest.Builder requestBuilder(URI uri, Map<String, String> defaults) {
    return requestBuilder(uri, defaults, true);
  }

  private HttpRequest.Builder requestBuilder(
      URI uri, Map<String, String> defaults, boolean boundedResponse) {
    var builder = HttpRequest.newBuilder(uri);
    for (var entry : config.effectiveHeaders(defaults).entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }
    if (boundedResponse && config.responseTimeout() != null) {
      builder.timeout(config.responseTimeout());
    }
    return builder;
  }

  private LinkedHashMap<String, String> authenticatedHeaders() {
    var headers = new LinkedHashMap<String, String>();
    if (!Strings.isBlank(config.apiKey())) {
      headers.put("x-goog-api-key", config.apiKey());
    }
    return headers;
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new GeminiException("Failed to serialize Gemini Files API request", e);
    }
  }

  private static ModelConfig requireConfig(ModelConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    if (Strings.isBlank(config.apiKey()) && Strings.isBlank(config.baseUrl())) {
      throw new IllegalArgumentException(
          "config with valid apiKey is required (or set baseUrl + auth header)");
    }
    return config;
  }

  private static HttpClient createHttpClient(ModelConfig config) {
    var validated = requireConfig(config);
    apiRoot(validated);
    return HttpClientFactory.create(validated, HttpClient.Redirect.NEVER);
  }

  private static URI apiRoot(ModelConfig config) {
    if (Strings.isBlank(config.baseUrl())) {
      return DEFAULT_API_ROOT;
    }
    var configured = parseAbsoluteUri(config.baseUrl(), "baseUrl must be a valid absolute URI");
    var scheme = configured.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("baseUrl must use the http or https scheme");
    }
    if (configured.getRawQuery() != null) {
      throw new IllegalArgumentException("baseUrl must not include a query string");
    }
    var value = configured.toString().replaceFirst("/+$", "");
    value = value.replaceFirst("/(?:v1|v1beta)$", "");
    return URI.create(value);
  }

  private URI endpoint(String path) {
    return URI.create(apiRoot + path);
  }

  private static URI parseAbsoluteUri(String value, String message) {
    try {
      var uri = URI.create(value);
      if (!uri.isAbsolute()
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null) {
        throw new IllegalArgumentException(message);
      }
      return uri;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(message, e);
    }
  }

  private static boolean sameOrigin(URI expected, URI actual) {
    return expected.getScheme().equalsIgnoreCase(actual.getScheme())
        && expected.getHost().equalsIgnoreCase(actual.getHost())
        && effectivePort(expected) == effectivePort(actual);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
  }

  private static Duration requireNonNegative(Duration value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    try {
      value.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(name + " is too large", e);
    }
    return value;
  }

  private record UploadFile(Path path, String displayName, String mimeType, long size) {}

  private record GeminiFileUploadResponse(GeminiFileResource file) {}

  private record GeminiFileResource(
      String name,
      @JsonProperty("mimeType") String mimeType,
      String uri,
      String state,
      GeminiFileStatus error) {}

  private record GeminiFileStatus(Integer code, String message) {}
}

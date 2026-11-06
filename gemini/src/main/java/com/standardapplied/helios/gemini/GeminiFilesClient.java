/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.common.HttpClientFactory;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.FileReference;
import com.standardapplied.helios.core.model.ModelConfig;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Client for streaming files to the Gemini Files API. An upload returns the caller's {@link
 * ManagedFile} once the bytes land; {@link ManagedFile#awaitActive()} waits for Gemini's
 * processing, and {@link ManagedFile#close()} or {@link ManagedFile#delete()} removes the file,
 * including after a failed wait. Helios never deletes a file on its own.
 */
public final class GeminiFilesClient implements AutoCloseable {

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
  private static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofMinutes(10);

  private final HttpClient httpClient;
  private final FilesEndpoint endpoint;
  private final FilesHttp http;
  private final FileActivation activation;
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
        FilesEndpoint.createHttpClient(config),
        DEFAULT_POLL_INTERVAL,
        DEFAULT_PROCESSING_TIMEOUT,
        true);
  }

  GeminiFilesClient(
      ModelConfig config,
      HttpClient httpClient,
      Duration pollInterval,
      Duration processingTimeout,
      boolean ownsHttpClient) {
    var validated = FilesEndpoint.requireConfig(config);
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    var poll = FileActivation.requireNonNegative(pollInterval, "pollInterval");
    this.processingTimeout =
        FileActivation.requireNonNegative(processingTimeout, "processingTimeout");
    this.endpoint = FilesEndpoint.of(validated);
    this.http = new FilesHttp(validated, httpClient, endpoint);
    this.activation = new FileActivation(http, poll);
    this.ownsHttpClient = ownsHttpClient;
  }

  /**
   * Streams a file to Gemini and returns its handle as soon as the bytes land, before Gemini has
   * processed it. The MIME type is probed from the file.
   *
   * @param path readable, non-empty file of at most 2 GB
   * @return the caller's handle; {@link ManagedFile#awaitActive()} waits for processing and {@link
   *     ManagedFile#close()} deletes the file
   * @throws IllegalArgumentException if the file is invalid or its MIME type cannot be detected
   * @throws GeminiException if the upload fails, in which case no file is left to delete
   */
  public ManagedFile upload(Path path) {
    return upload(path, inferMimeType(path));
  }

  /**
   * Streams a file with an explicit MIME type and returns its handle as soon as the bytes land,
   * before Gemini has processed it.
   *
   * @param path readable, non-empty file of at most 2 GB
   * @param mimeType valid media type such as {@code video/mp4}
   * @return the caller's handle; {@link ManagedFile#awaitActive()} waits for processing and {@link
   *     ManagedFile#close()} deletes the file
   * @throws IllegalArgumentException if the file or MIME type is invalid
   * @throws GeminiException if the upload fails, in which case no file is left to delete
   */
  public ManagedFile upload(Path path, String mimeType) {
    var file = UploadFile.of(path, mimeType);
    var uploaded =
        exchange(() -> ResumableUpload.upload(http, endpoint, file), "File upload interrupted");
    return new ManagedFile(http, activation, uploaded, processingTimeout);
  }

  @Override
  public void close() {
    if (ownsHttpClient) {
      HttpClientFactory.shutdownGracefully(httpClient);
    }
  }

  private String inferMimeType(Path path) {
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
    return mimeType;
  }

  private static <T> T exchange(FilesExchange<T> call, String interrupted) {
    try {
      return call.run();
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with the Gemini Files API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException(interrupted, e);
    }
  }

  @FunctionalInterface
  private interface FilesExchange<T> {
    T run() throws IOException, InterruptedException;
  }

  /**
   * The caller's handle on an uploaded file: it waits for Gemini's processing and deletes the file.
   * The file is the caller's until {@link #delete()} or {@link #close()} removes it, whether or not
   * processing succeeded; Helios never deletes it on its own.
   */
  public static final class ManagedFile implements AutoCloseable {

    private final FilesHttp http;
    private final FileActivation activation;
    private final GeminiFileResource uploaded;
    private final String resourceName;
    private final Duration processingTimeout;
    private volatile FileReference reference;
    private volatile boolean deleted;

    private ManagedFile(
        FilesHttp http,
        FileActivation activation,
        GeminiFileResource uploaded,
        Duration processingTimeout) {
      this.http = http;
      this.activation = activation;
      this.uploaded = uploaded;
      this.resourceName = GeminiFileResource.requireName(uploaded.name());
      this.processingTimeout = processingTimeout;
    }

    /**
     * The name the Files API gave the upload, the key its delete and status requests use; checked
     * when the handle was created, so it always has the {@code files/{id}} form.
     *
     * @return the resource name, never {@code null} or blank
     */
    public String resourceName() {
      return resourceName;
    }

    /**
     * Waits up to the client's processing timeout for Gemini to finish processing the file.
     *
     * @return the model-facing reference
     * @see #awaitActive(Duration)
     */
    public FileReference awaitActive() {
      return awaitActive(processingTimeout);
    }

    /**
     * Waits up to {@code timeout} for Gemini to finish processing the file, starting from the state
     * the upload reported, so a file already active needs no request. Once active, the reference is
     * kept and later calls return it with no request. When the wait fails the file still exists:
     * {@link #delete()} removes it, or this method waits again. A {@link #delete()} from another
     * thread is not blocked by a wait in progress.
     *
     * @param timeout maximum processing wait
     * @return the model-facing reference
     * @throws IllegalArgumentException if {@code timeout} is negative or too large
     * @throws IllegalStateException if the file was deleted
     * @throws GeminiException if processing fails, enters an unknown state, times out or is active
     *     without a URI or MIME type, if a poll fails, or if the waiting thread is interrupted
     */
    public FileReference awaitActive(Duration timeout) {
      var validated = FileActivation.requireNonNegative(timeout, "timeout");
      if (deleted) {
        throw new IllegalStateException("Gemini file " + resourceName + " was deleted");
      }
      var active = reference;
      if (active == null) {
        active =
            exchange(
                () -> activation.await(resourceName, uploaded, validated),
                "Gemini file processing wait interrupted");
        reference = active;
      }
      return active;
    }

    /**
     * Deletes the file. Repeated calls after a success perform no request; a failed call can be
     * retried.
     *
     * @throws GeminiException if the API refuses or cannot be reached
     */
    public synchronized void delete() {
      if (deleted) {
        return;
      }
      http.delete(resourceName);
      deleted = true;
    }

    /** Deletes the file, as {@link #delete()} does. */
    @Override
    public void close() {
      delete();
    }
  }
}

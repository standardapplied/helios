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

/** Client for streaming files to the Gemini Files API and waiting for processing to complete. */
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
   * Streams a file to Gemini and waits until it is ready for use in an interaction.
   *
   * @param path readable, non-empty file of at most 2 GB
   * @return the provider file reference for a model message
   * @throws IllegalArgumentException if the file is invalid or its MIME type cannot be detected
   * @throws GeminiException if upload or processing fails
   */
  public FileReference upload(Path path) {
    return upload(path, inferMimeType(path));
  }

  /**
   * Streams a file to Gemini and returns a managed reference whose {@link ManagedFile#close()}
   * deletes the provider resource.
   */
  public ManagedFile uploadManaged(Path path) {
    return uploadManaged(path, inferMimeType(path));
  }

  /**
   * Streams a file with an explicit MIME type and waits for Gemini's processing to finish.
   *
   * @param path readable, non-empty file of at most 2 GB
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
   * @param path readable, non-empty file of at most 2 GB
   * @param mimeType valid media type such as {@code video/mp4}
   * @param timeout maximum provider-side processing wait
   * @return the provider file reference for a model message
   * @throws IllegalArgumentException if an input is invalid
   * @throws GeminiException if upload or processing fails
   */
  public FileReference upload(Path path, String mimeType, Duration timeout) {
    return uploadResource(path, mimeType, timeout).reference();
  }

  /**
   * Streams a file with an explicit MIME type and returns a managed provider resource.
   *
   * @param path readable, non-empty file of at most 2 GB
   * @param mimeType valid media type such as {@code video/mp4}
   * @return managed file reference; close it to delete the provider resource
   */
  public ManagedFile uploadManaged(Path path, String mimeType) {
    return uploadManaged(path, mimeType, processingTimeout);
  }

  /** Streams a file, waits up to the supplied duration, and returns a managed provider resource. */
  public ManagedFile uploadManaged(Path path, String mimeType, Duration timeout) {
    var uploaded = uploadResource(path, mimeType, timeout);
    return new ManagedFile(http, uploaded.reference(), uploaded.resourceName());
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

  private FileActivation.ReadyFile uploadResource(Path path, String mimeType, Duration timeout) {
    var file = UploadFile.of(path, mimeType);
    var validatedTimeout = FileActivation.requireNonNegative(timeout, "timeout");
    try {
      return activation.await(ResumableUpload.upload(http, endpoint, file), validatedTimeout);
    } catch (IOException e) {
      throw new GeminiException("Failed to communicate with the Gemini Files API", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("File upload interrupted", e);
    }
  }

  /** A provider file reference with deterministic, idempotent lifecycle management. */
  public static final class ManagedFile implements AutoCloseable {

    private final FilesHttp http;
    private final FileReference reference;
    private final String resourceName;
    private boolean deleted;

    private ManagedFile(FilesHttp http, FileReference reference, String resourceName) {
      this.http = http;
      this.reference = reference;
      this.resourceName = resourceName;
    }

    /** Model-facing file reference. */
    public FileReference reference() {
      return reference;
    }

    /** Validated Gemini resource name in {@code files/{id}} form. */
    public String resourceName() {
      return resourceName;
    }

    /** Deletes the provider resource. Repeated successful calls perform no additional request. */
    public synchronized void delete() {
      if (deleted) {
        return;
      }
      http.delete(resourceName);
      deleted = true;
    }

    /** Deletes the provider resource. */
    @Override
    public void close() {
      delete();
    }
  }
}

/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

/**
 * Streams a file to the Gemini Files API with its resumable protocol: a {@code start} request
 * announcing the file returns an upload URL, and one {@code upload, finalize} request streams the
 * bytes to it.
 */
final class ResumableUpload {

  private record UploadResponse(GeminiFileResource file) {}

  private ResumableUpload() {}

  /** The resource of {@code file}, uploaded through {@code http}. */
  static GeminiFileResource upload(FilesHttp http, FilesEndpoint endpoint, UploadFile file)
      throws IOException, InterruptedException {
    return sendBytes(http, start(http, endpoint, file), file);
  }

  private static URI start(FilesHttp http, FilesEndpoint endpoint, UploadFile file)
      throws IOException, InterruptedException {
    var body = http.serialize(Map.of("file", Map.of("display_name", file.displayName())));
    var headers = http.authenticatedHeaders();
    headers.put("Content-Type", "application/json");
    headers.put("X-Goog-Upload-Protocol", "resumable");
    headers.put("X-Goog-Upload-Command", "start");
    headers.put("X-Goog-Upload-Header-Content-Length", Long.toString(file.size()));
    headers.put("X-Goog-Upload-Header-Content-Type", file.mimeType());
    var response =
        http.send(
            http.request("/upload/v1beta/files", headers)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    http.discard(response);
    return endpoint.uploadUrl(response.headers().firstValue("x-goog-upload-url").orElse(null));
  }

  /** Streams the bytes, without a response timeout: a large file takes as long as it takes. */
  private static GeminiFileResource sendBytes(FilesHttp http, URI uploadUrl, UploadFile file)
      throws IOException, InterruptedException {
    var headers = http.authenticatedHeaders();
    headers.put("Content-Type", file.mimeType());
    headers.put("X-Goog-Upload-Offset", "0");
    headers.put("X-Goog-Upload-Command", "upload, finalize");
    var response =
        http.send(
            http.request(uploadUrl, headers, false)
                .POST(HttpRequest.BodyPublishers.ofFile(file.path()))
                .build());
    var uploaded = http.readJson(response, UploadResponse.class);
    if (uploaded.file() == null) {
      throw new GeminiException("Gemini Files API upload response did not contain a file resource");
    }
    return uploaded.file();
  }
}

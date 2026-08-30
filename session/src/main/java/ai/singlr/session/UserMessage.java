/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.FileReference;
import ai.singlr.core.model.InlineFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A message from the user to the agent session.
 *
 * <p>Carries the user's text plus inline {@link InlineFile} attachments and provider-accessible
 * {@link FileReference} values. The session's agent loop preserves both across the {@link
 * UserMessage} to model-message conversion so each provider adapter receives the intended media.
 *
 * <p>Construct via {@link #text(String)} for the common plain-text case, or {@link #newBuilder()}
 * for messages carrying attachments. The builder's path-based helper sniffs the media type via
 * {@link Files#probeContentType(Path)} with a fallback table for common extensions, so callers can
 * write {@code UserMessage.newBuilder().withText("look at this").withAttachment(path).build()}
 * without worrying about MIME-type wiring.
 *
 * <p>{@code UserMessage} carries no timestamp or sender identity — the agent loop captures those
 * via {@code QueryEvent.UserMessageReceived} when the message is observed.
 *
 * @param text the message text; non-null, may be empty (callers attaching files often skip prose)
 * @param attachments inline file attachments; non-null, defensively copied, may be empty
 * @param fileReferences provider-accessible file references; defensively copied, null is treated as
 *     empty for serialized backward compatibility
 */
public record UserMessage(
    String text, List<InlineFile> attachments, List<FileReference> fileReferences) {

  public UserMessage(String text, List<InlineFile> attachments) {
    this(text, attachments, List.of());
  }

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if text or attachments is null
   * @throws IllegalArgumentException if the message has no text, attachments, or file references
   */
  public UserMessage {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(attachments, "attachments must not be null");
    fileReferences = fileReferences == null ? List.of() : fileReferences;
    for (var a : attachments) {
      Objects.requireNonNull(a, "attachments must not contain null");
    }
    for (var reference : fileReferences) {
      Objects.requireNonNull(reference, "fileReferences must not contain null");
    }
    if (Strings.isBlank(text) && attachments.isEmpty() && fileReferences.isEmpty()) {
      throw new IllegalArgumentException(
          "UserMessage must carry non-blank text, an attachment, or a file reference");
    }
    attachments = List.copyOf(attachments);
    fileReferences = List.copyOf(fileReferences);
  }

  /**
   * Convenience factory for a text-only user message.
   *
   * @param text the message text; non-blank
   * @return a fresh {@code UserMessage} with no attachments
   */
  public static UserMessage text(String text) {
    Objects.requireNonNull(text, "text must not be null");
    if (Strings.isBlank(text)) {
      throw new IllegalArgumentException("text must not be blank");
    }
    return new UserMessage(text, List.of(), List.of());
  }

  /**
   * Start building a {@code UserMessage}. Use {@link Builder#withText} and {@link
   * Builder#withAttachment} to assemble.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Whether this message carries any attachments.
   *
   * @return {@code true} if {@link #attachments()} is non-empty
   */
  public boolean hasAttachments() {
    return !attachments.isEmpty();
  }

  public boolean hasFileReferences() {
    return !fileReferences.isEmpty();
  }

  /** Mutable builder for {@link UserMessage}. */
  public static final class Builder {

    private String text = "";
    private final List<InlineFile> attachments = new ArrayList<>();
    private final List<FileReference> fileReferences = new ArrayList<>();

    private Builder() {}

    /**
     * Set the message text.
     *
     * @param text the text; non-null, may be empty if attachments are present
     * @return this builder
     */
    public Builder withText(String text) {
      this.text = Objects.requireNonNull(text, "text must not be null");
      return this;
    }

    /**
     * Attach raw bytes with an explicit media type.
     *
     * @param data the file bytes; non-null, non-empty
     * @param mediaType the IANA media type (e.g. {@code "image/png"}); non-blank
     * @return this builder
     */
    public Builder withAttachment(byte[] data, String mediaType) {
      attachments.add(InlineFile.of(data, mediaType));
      return this;
    }

    /**
     * Attach a pre-built {@link InlineFile}. Useful when the bytes and media type were resolved by
     * the caller.
     *
     * @param attachment the attachment; non-null
     * @return this builder
     */
    public Builder withAttachment(InlineFile attachment) {
      attachments.add(Objects.requireNonNull(attachment, "attachment must not be null"));
      return this;
    }

    public Builder withFileReference(FileReference fileReference) {
      fileReferences.add(Objects.requireNonNull(fileReference, "fileReference must not be null"));
      return this;
    }

    /**
     * Attach a file from disk. Reads the bytes and sniffs the media type via {@link
     * Files#probeContentType(Path)} with a fallback to a small extension table for the formats
     * commonly used in agentic contexts ({@code .md}, {@code .csv}, {@code .json}, etc.).
     *
     * @param path the file path; non-null, must exist as a regular file
     * @return this builder
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the media type cannot be determined
     */
    public Builder withAttachment(Path path) throws IOException {
      Objects.requireNonNull(path, "path must not be null");
      var bytes = Files.readAllBytes(path);
      var media = detectMediaType(path);
      if (media == null) {
        throw new IllegalArgumentException(
            "could not determine media type for " + path + " — supply it explicitly");
      }
      attachments.add(InlineFile.of(bytes, media));
      return this;
    }

    /**
     * Build the immutable message.
     *
     * @return the message
     * @throws IllegalArgumentException if both {@code text} is blank and no attachments were added
     */
    public UserMessage build() {
      return new UserMessage(text, List.copyOf(attachments), List.copyOf(fileReferences));
    }
  }

  /**
   * Detect the media type for a path: try {@link Files#probeContentType}, then fall back to a
   * curated extension table.
   *
   * @param path the file path; non-null
   * @return the media type, or null if undetected
   */
  static String detectMediaType(Path path) {
    try {
      var probed = Files.probeContentType(path);
      if (probed != null && !Strings.isBlank(probed)) {
        return probed;
      }
    } catch (IOException ignored) {
      // fall through to extension table
    }
    var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    var dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return null;
    }
    return EXTENSION_TABLE.get(name.substring(dot + 1));
  }

  private static final Map<String, String> EXTENSION_TABLE =
      Map.ofEntries(
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("gif", "image/gif"),
          Map.entry("webp", "image/webp"),
          Map.entry("bmp", "image/bmp"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("pdf", "application/pdf"),
          Map.entry("txt", "text/plain"),
          Map.entry("md", "text/markdown"),
          Map.entry("markdown", "text/markdown"),
          Map.entry("csv", "text/csv"),
          Map.entry("tsv", "text/tab-separated-values"),
          Map.entry("json", "application/json"),
          Map.entry("yaml", "application/yaml"),
          Map.entry("yml", "application/yaml"),
          Map.entry("xml", "application/xml"),
          Map.entry("html", "text/html"),
          Map.entry("htm", "text/html"));
}

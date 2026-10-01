/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link FileTracker}. Per-session lifetime; created at session start, discarded at
 * session end. {@link ConcurrentHashMap} backs the read/write maps for thread-safe access from the
 * agent-loop thread plus observability readers.
 */
public final class InMemoryFileTracker implements FileTracker {

  private final ConcurrentHashMap<Path, FileFingerprint> reads = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Path, FileFingerprint> writes = new ConcurrentHashMap<>();

  private InMemoryFileTracker() {}

  /**
   * @return a fresh empty tracker
   */
  public static InMemoryFileTracker create() {
    return new InMemoryFileTracker();
  }

  @Override
  public void recordRead(Path path, FileFingerprint fingerprint) {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    reads.put(path, fingerprint);
  }

  @Override
  public void recordWrite(Path path, FileFingerprint fingerprint) {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    writes.put(path, fingerprint);
  }

  @Override
  public boolean hasReadInSession(Path path) {
    Objects.requireNonNull(path, "path must not be null");
    return reads.containsKey(path);
  }

  @Override
  public Optional<FileFingerprint> fingerprintAtLastRead(Path path) {
    Objects.requireNonNull(path, "path must not be null");
    return Optional.ofNullable(reads.get(path));
  }

  /**
   * The fingerprint captured at the most recent {@code recordWrite} for {@code path}, if any.
   *
   * @param path absolute, normalised path; non-null
   * @return the last-write fingerprint, or empty if never written this session
   */
  public Optional<FileFingerprint> fingerprintAtLastWrite(Path path) {
    Objects.requireNonNull(path, "path must not be null");
    return Optional.ofNullable(writes.get(path));
  }

  /**
   * Number of distinct paths recorded as read this session.
   *
   * @return non-negative
   */
  public int readCount() {
    return reads.size();
  }

  /**
   * Number of distinct paths recorded as written this session.
   *
   * @return non-negative
   */
  public int writeCount() {
    return writes.size();
  }
}

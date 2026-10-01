/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.memory;

import java.io.IOException;
import java.util.List;

/**
 * The {@link MemoryBackend#disabled()} sentinel implementation. Refuses every operation with a
 * {@link IOException} whose message names the offending method so misconfigurations are obvious in
 * tool-result text and logs.
 *
 * <p>Package-private; callers reach this through {@link MemoryBackend#disabled()}.
 */
final class DisabledMemoryBackend implements MemoryBackend {

  static final DisabledMemoryBackend INSTANCE = new DisabledMemoryBackend();

  private DisabledMemoryBackend() {}

  @Override
  public String view(String path) throws IOException {
    throw refuse("view", path);
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    throw refuse("list", prefix);
  }

  @Override
  public void create(String path, String content) throws IOException {
    throw refuse("create", path);
  }

  @Override
  public void strReplace(String path, String oldString, String newString) throws IOException {
    throw refuse("strReplace", path);
  }

  @Override
  public void insert(String path, int lineNumber, String content) throws IOException {
    throw refuse("insert", path);
  }

  @Override
  public void delete(String path) throws IOException {
    throw refuse("delete", path);
  }

  @Override
  public String toString() {
    return "MemoryBackend.disabled()";
  }

  private static IOException refuse(String op, String path) {
    return new IOException(
        "MemoryBackend.disabled(): "
            + op
            + "("
            + path
            + ") refused — this session was configured with memory turned off");
  }
}

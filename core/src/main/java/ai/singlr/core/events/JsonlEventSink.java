/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.events;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link EventSink} that writes one JSON line per event to a file.
 *
 * <p>Useful for session replay, post-hoc audit, and long-running observability where a UI process
 * subscribes lazily by tailing the file. Hand-rolled JSONL — no Jackson dependency in {@code core}.
 *
 * <p>The sink is thread-safe: a single {@link ReentrantLock} serializes writes so concurrent
 * emitters cannot interleave bytes. Each {@link #onEvent} call is one append + one flush, ensuring
 * a crashing producer does not leave a partial line in the file.
 *
 * <p>Closing the sink flushes and closes the underlying writer. Sinks opened with {@link
 * #open(Path)} are {@link AutoCloseable} — use try-with-resources or explicit {@link #close()}.
 */
public final class JsonlEventSink implements EventSink, AutoCloseable {

  private final BufferedWriter writer;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile boolean closed;

  private JsonlEventSink(BufferedWriter writer) {
    this.writer = writer;
  }

  /**
   * Opens (or creates, appending) a JSONL file for writing events.
   *
   * @param path destination file; parent directory must exist
   * @return an open sink; close with {@link #close()} or try-with-resources
   * @throws UncheckedIOException if the file cannot be opened for writing
   */
  public static JsonlEventSink open(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      var writer =
          Files.newBufferedWriter(
              path,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND);
      return new JsonlEventSink(writer);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to open JSONL event file: " + path, e);
    }
  }

  @Override
  public void onEvent(HeliosEvent event) {
    Objects.requireNonNull(event, "event");
    if (closed) {
      throw new IllegalStateException("JsonlEventSink is closed");
    }
    var line = EventJsonWriter.encode(event);
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("JsonlEventSink is closed");
      }
      writer.write(line);
      writer.newLine();
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write event line", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    lock.lock();
    try {
      closed = true;
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to close JSONL event file", e);
    } finally {
      lock.unlock();
    }
  }

  /** Visible for tests. */
  boolean isClosed() {
    return closed;
  }
}

/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One session's JSON-lines file of a {@link FileSessionStore}, never opened through a symbolic
 * link: its complete lines read as a {@link Log}, and a batch appended under an exclusive {@link
 * java.nio.channels.FileLock}. The caller holds this process's lock on the file.
 */
final class SessionFile {

  private static final Set<OpenOption> APPEND =
      Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

  private static final Set<OpenOption> CREATE_OR_APPEND =
      Set.of(
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);

  private final Path path;
  private final FileAttribute<?>[] attributes;

  /**
   * @param path the file
   * @param attributes what the file is created with
   */
  SessionFile(Path path, FileAttribute<?>[] attributes) {
    this.path = path;
    this.attributes = attributes;
  }

  /**
   * The file's complete lines.
   *
   * @return the log; empty for a file that does not exist
   * @throws SessionStoreException if the file cannot be read or holds a line that is not a batch
   */
  Log read() {
    try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      return Log.parse(path, contents(channel));
    } catch (NoSuchFileException e) {
      return new Log(List.of(), 0, 0);
    } catch (IOException e) {
      throw new SessionStoreException("cannot read session file " + path, e);
    }
  }

  /**
   * Append {@code batch} as one line, if the file's last sequence number is {@code
   * expectedLastSeq}, and force it to the device. The last sequence number is read from the file,
   * truncating a last line a write did not finish.
   *
   * @throws SessionConflictException if the file's last sequence number is not {@code
   *     expectedLastSeq}; nothing is written
   * @throws SessionStoreException if the file cannot be read or written
   */
  void append(String sessionId, long expectedLastSeq, List<SessionEntry> batch) {
    var line =
        (SessionEntryJson.write(new SessionEntryJson.Batch(expectedLastSeq + 1, batch)) + "\n")
            .getBytes(UTF_8);
    try (var channel =
            FileChannel.open(path, expectedLastSeq == 0 ? CREATE_OR_APPEND : APPEND, attributes);
        var _ = channel.lock()) {
      var lastSeq = lastSeq(channel);
      if (lastSeq != expectedLastSeq) {
        throw new SessionConflictException(sessionId, expectedLastSeq, lastSeq);
      }
      var end = channel.size();
      var bytes = ByteBuffer.wrap(line);
      while (bytes.hasRemaining()) {
        channel.write(bytes, end + bytes.position());
      }
      channel.force(false);
    } catch (NoSuchFileException e) {
      throw new SessionConflictException(sessionId, expectedLastSeq, 0);
    } catch (IOException e) {
      throw new SessionStoreException("cannot append to session file " + path, e);
    }
  }

  private long lastSeq(FileChannel channel) throws IOException {
    var log = Log.parse(path, contents(channel));
    if (log.end() < channel.size()) {
      channel.truncate(log.end());
    }
    return log.lastSeq();
  }

  private static byte[] contents(FileChannel channel) throws IOException {
    return Channels.newInputStream(channel.position(0)).readAllBytes();
  }

  /**
   * A file's complete lines: their entries, the last sequence number, and the offset after the last
   * {@code \n}, past which a write did not finish.
   */
  record Log(List<SessionEntry> entries, long lastSeq, long end) {

    static Log parse(Path file, byte[] bytes) {
      var entries = new ArrayList<SessionEntry>();
      long lastSeq = 0;
      var start = 0;
      var lineNumber = 0;
      for (var i = 0; i < bytes.length; i++) {
        if (bytes[i] == '\n') {
          lineNumber++;
          var batch = batch(file, lineNumber, ByteBuffer.wrap(bytes, start, i - start));
          if (batch.seq() != lastSeq + 1) {
            throw corrupt(file, lineNumber, batch.seq(), lastSeq + 1);
          }
          entries.addAll(batch.entries());
          lastSeq += batch.entries().size();
          start = i + 1;
        }
      }
      return new Log(List.copyOf(entries), lastSeq, start);
    }

    private static SessionEntryJson.Batch batch(Path file, int lineNumber, ByteBuffer line) {
      try {
        return SessionEntryJson.readBatch(UTF_8.newDecoder().decode(line).toString());
      } catch (CharacterCodingException | SessionStoreException e) {
        throw new SessionStoreException(
            "session file " + file + " line " + lineNumber + " is not a batch", e);
      }
    }

    private static SessionStoreException corrupt(
        Path file, int lineNumber, long seq, long expected) {
      return new SessionStoreException(
          "session file "
              + file
              + " line "
              + lineNumber
              + " starts at sequence number "
              + seq
              + ", expected "
              + expected,
          null);
    }
  }
}

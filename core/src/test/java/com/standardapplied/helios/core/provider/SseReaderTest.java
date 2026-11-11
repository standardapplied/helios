/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import static com.standardapplied.helios.core.provider.SseFixtures.NEVER_IDLE;
import static com.standardapplied.helios.core.provider.SseFixtures.awaitNoWatchdogBut;
import static com.standardapplied.helios.core.provider.SseFixtures.stream;
import static com.standardapplied.helios.core.provider.SseFixtures.watchdogStates;
import static com.standardapplied.helios.core.provider.SseFixtures.watchdogs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.provider.SseFixtures.Closing;
import com.standardapplied.helios.core.provider.SseFixtures.EchoParser;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.Uninterruptible;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SseReaderTest {

  private static final class TrackingStream extends ByteArrayInputStream {
    private boolean closed;

    TrackingStream(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
      closed = true;
      throw new IOException("close failed");
    }
  }

  /** Reads every unframed last line as {@code answer}, recording each line it is offered. */
  private static final class LineParser extends EchoParser {
    final List<String> lines = new ArrayList<>();
    private final StreamEvent answer;

    LineParser(StreamEvent answer) {
      this.answer = answer;
    }

    @Override
    public StreamEvent unframedLine(String line) {
      lines.add(line);
      return answer;
    }
  }

  /** A body whose read blocks through interrupts and fails only once the body is closed. */
  private static final class ClosedOnlyByClose extends InputStream {
    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      reading.countDown();
      Uninterruptible.await(closed);
      throw new IOException("body closed");
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }

  @Test
  void aBodyClosedMidStreamEndsInAnErrorCarryingWhatArrivedAndIsClosedByTheReader() {
    var feed = new FeedableInputStream();
    feed.feed("data: one\n\n");
    feed.end();
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var events = drain(reader);

    assertEquals(new StreamEvent.TextDelta("one"), events.getFirst());
    var error = assertInstanceOf(StreamEvent.Error.class, events.getLast());
    assertInstanceOf(EOFException.class, error.cause());
    assertEquals("one", error.partial().content());
    assertTrue(body.closed);
  }

  @Test
  void yieldsTheDataOfEachEventUntilTheParsersTerminalAndClosesTheBody() {
    var body =
        new TrackingStream(
            "event: x\ndata: one\n\n"
                + ": a comment\n\n"
                + "data:    \n\n"
                + "data: [DONE]\n\n"
                + "id: 7\nretry: 10\ndata:  two  \n\n"
                + "data: silent\n\n"
                + "data: stop\n\n"
                + "data: never\n\n");
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    assertEquals(
        List.of(
            new StreamEvent.TextDelta("one"),
            new StreamEvent.KeepAlive(),
            new StreamEvent.KeepAlive(),
            new StreamEvent.KeepAlive(),
            new StreamEvent.TextDelta("two"),
            new StreamEvent.KeepAlive(),
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        drain(reader));
    assertEquals(List.of("one", "two", "silent", "stop"), parser.payloads);
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void everyEventWithoutContentIsAKeepAliveTheParserNeverSees() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream(
                ": ping\n\n"
                    + "event: ping\n\n"
                    + "data:\n\n"
                    + "data: [DONE]\n\n"
                    + "data: silent\n\n"
                    + "data: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    var events = drain(reader);

    var keepAlive = new StreamEvent.KeepAlive();
    assertEquals(
        List.of(
            keepAlive,
            keepAlive,
            keepAlive,
            keepAlive,
            keepAlive,
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        events);
    assertEquals(List.of("silent", "stop"), parser.payloads);
  }

  @Test
  void aBlankLineThatEndsNoEventYieldsNothing() {
    var reader =
        new SseReader(
            stream("\n\r\n\ndata: one\n\n\n\ndata: stop\n\n"),
            NEVER_IDLE,
            new EchoParser(),
            ProviderException::new);

    assertEquals(
        List.of(
            new StreamEvent.TextDelta("one"),
            new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        drain(reader));
  }

  @Test
  void readsDataWithAndWithoutTheSpaceAndJoinsSeveralDataLinesWithANewline() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data:{\"a\":\ndataset: no\ndata\ndata:  1}\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("{\"a\":\n\n 1}", "stop"), parser.payloads);
  }

  @Test
  void endsLinesAtCarriageReturnLineFeedOrEitherWhereverTheReadsSplitThem() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            byteByByte(
                "data: one\r\ndata: more\r\n\r\ndata: two\r\rdata: three\n\ndata: stop\r\n\r\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("one\nmore", "two", "three", "stop"), parser.payloads);
  }

  @Test
  void dropsOneByteOrderMarkAtTheStartOfTheBodyAndKeepsEveryOther() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("﻿data: one\n\n﻿data: lost\n\ndata: ﻿two\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    drain(reader);

    assertEquals(List.of("one", "﻿two", "stop"), parser.payloads);
  }

  @Test
  void aParserErrorEndsTheStreamAndClosesTheBody() {
    var body = new TrackingStream("data: one\n\ndata: fail\n\ndata: never\n\n");
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    var events = drain(reader);

    assertEquals(new StreamEvent.TextDelta("one"), events.getFirst());
    assertEquals("failed", assertInstanceOf(StreamEvent.Error.class, events.getLast()).message());
    assertEquals(2, events.size());
    assertEquals(List.of("one", "fail"), parser.payloads);
    assertTrue(body.closed);
  }

  @Test
  void aBodyThatEndsBeforeTheTerminalEndsInAnErrorCarryingThePartialResponse() {
    var body = new TrackingStream("data: one\n\ndata: two\n\n");
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var events = drain(reader);

    assertEquals(
        List.of(new StreamEvent.TextDelta("one"), new StreamEvent.TextDelta("two")),
        events.subList(0, 2));
    var error = assertInstanceOf(StreamEvent.Error.class, events.get(2));
    assertEquals("Stream ended before its terminal event", error.message());
    assertEquals(
        "the response body ended before the provider's terminal event",
        assertInstanceOf(EOFException.class, error.cause()).getMessage());
    assertEquals("one|two", error.partial().content());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals(3, events.size());
    assertTrue(body.closed);
  }

  @Test
  void anEmptyBodyEndsInAnErrorWithNothingProduced() {
    var reader = new SseReader(stream(""), NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertInstanceOf(EOFException.class, error.cause());
    assertEquals("", error.partial().content());
    assertFalse(reader.hasNext());
  }

  @Test
  void anEventTheBodyCutsOffBeforeItsBlankLineIsNeverParsed() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\n\ndata: stop\n"), NEVER_IDLE, parser, ProviderException::new);

    var events = drain(reader);

    assertInstanceOf(EOFException.class, ((StreamEvent.Error) events.getLast()).cause());
    assertEquals(List.of("one"), parser.payloads);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\n", "\r\n", "\n \t", "\n \t\n", "\r\n \r\n\t"})
  void aBodyEndingWithALineOutsideTheFormatEndsInTheTerminalTheParserReadsInIt(String end) {
    var done = new StreamEvent.Done(Response.newBuilder().withContent("blocked").build());
    var parser = new LineParser(done);
    var body = new Closing(stream("data: one\n\n{\"error\":{\"code\":1}}" + end));
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    var events = drain(reader);

    assertEquals(List.of(new StreamEvent.TextDelta("one"), done), events);
    assertEquals(List.of("{\"error\":{\"code\":1}}"), parser.lines);
    assertTrue(body.closed);
  }

  @Test
  void anErrorTheParserReadsInTheUnframedLastLineIsTheStreamsOnlyTerminal() {
    var failure =
        new StreamEvent.Error(
            "refused", null, Response.newBuilder().withFinishReason(FinishReason.ERROR).build());
    var body = new Closing(stream("junk"));
    var reader = new SseReader(body, NEVER_IDLE, new LineParser(failure), ProviderException::new);

    assertEquals(List.of(failure), drain(reader));
    assertTrue(body.closed);
  }

  @ParameterizedTest
  @MethodSource("notTerminal")
  void anUnframedLastLineTheParserReadsAsNoTerminalEndsAsABodyThatEndedEarly(EchoParser parser) {
    var body = new Closing(stream("data: one\n\njunk\n"));
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    var events = drain(reader);

    assertEquals(2, events.size());
    var error = assertInstanceOf(StreamEvent.Error.class, events.getLast());
    assertInstanceOf(EOFException.class, error.cause());
    assertEquals("one", error.partial().content());
    assertTrue(body.closed);
  }

  static List<EchoParser> notTerminal() {
    return List.of(
        new EchoParser(), new LineParser(null), new LineParser(new StreamEvent.TextDelta("x")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "data: {\"error\":1",
        "data",
        "event: x",
        "id",
        "retry: 10",
        ": comment",
        "junk\ndata: one\n\n"
      })
  void aLastLineOfTheFormatIsNeverOfferedToTheParser(String last) {
    var parser = new LineParser(new StreamEvent.Done(Response.newBuilder().build()));
    var reader = new SseReader(stream(last), NEVER_IDLE, parser, ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, drain(reader).getLast());

    assertInstanceOf(EOFException.class, error.cause());
    assertEquals(List.of(), parser.lines);
  }

  @Test
  void aLineOutsideTheFormatBeforeTheTerminalIsIgnored() {
    var parser = new LineParser(new StreamEvent.Done(Response.newBuilder().build()));
    var reader =
        new SseReader(
            stream("{\"error\":1}\ndata: stop\n\n"), NEVER_IDLE, parser, ProviderException::new);

    assertEquals(
        List.of(new StreamEvent.Done(Response.newBuilder().withContent("done").build())),
        drain(reader));
    assertEquals(List.of(), parser.lines);
  }

  @Test
  void aLineAndAnEventOfExactlyTheFrameBoundAreRead() {
    var line = "data:" + "x".repeat(SseFrames.MAX_FRAME_CHARS - 5);
    var halves =
        "data:"
            + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2)
            + "\ndata:"
            + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2 - 1);
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream(line + "\n\n" + halves + "\n\ndata: stop\n\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);

    assertInstanceOf(StreamEvent.Done.class, drain(reader).getLast());
    assertEquals(
        List.of(SseFrames.MAX_FRAME_CHARS - 5, SseFrames.MAX_FRAME_CHARS, 4),
        parser.payloads.stream().map(String::length).toList());
  }

  @Test
  void aLineOneCharacterOverTheFrameBoundEndsInTheProvidersException() {
    var line = "data:" + "x".repeat(SseFrames.MAX_FRAME_CHARS - 4);
    var body = new Closing(stream(line + "\n\n"));
    var parser = new EchoParser();
    var reader = new SseReader(body, NEVER_IDLE, parser, ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream line exceeds 16777216 characters", error.message());
    assertTrue(body.closed);
    assertEquals(
        error.message(), assertInstanceOf(ProviderException.class, error.cause()).getMessage());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertEquals(List.of(), parser.payloads);
    assertFalse(reader.hasNext());
  }

  @Test
  void aLineThatNeverEndsFailsAtTheFrameBoundInsteadOfBufferingTheBody() {
    var reader =
        new SseReader(
            endless("data: ".getBytes(StandardCharsets.UTF_8), (byte) 'x'),
            NEVER_IDLE,
            new EchoParser(),
            ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream line exceeds 16777216 characters", error.message());
  }

  @Test
  void anEventOneCharacterOverTheFrameBoundEndsInTheProvidersExceptionWithThePartial() {
    var half = "data: " + "x".repeat(SseFrames.MAX_FRAME_CHARS / 2) + "\n";
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\n\n" + half + half + "\n"),
            NEVER_IDLE,
            parser,
            ProviderException::new);
    reader.next();

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream event exceeds 16777216 characters", error.message());
    assertInstanceOf(ProviderException.class, error.cause());
    assertEquals("one", error.partial().content());
    assertEquals(List.of("one"), parser.payloads);
  }

  @Test
  void hasNextAsksOnceUntilTheEventIsTaken() {
    var parser = new EchoParser();
    var reader =
        new SseReader(
            stream("data: one\n\ndata: two\n\n"), NEVER_IDLE, parser, ProviderException::new);

    assertTrue(reader.hasNext());
    assertTrue(reader.hasNext());

    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertEquals(List.of("one"), parser.payloads);
  }

  @Test
  void nextWithoutHasNextReadsTheNextEvent() {
    var reader =
        new SseReader(
            stream("data: one\n\ndata: stop\n\n"),
            NEVER_IDLE,
            new EchoParser(),
            ProviderException::new);

    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertInstanceOf(StreamEvent.Done.class, reader.next());
  }

  @Test
  void theTerminalIsYieldedOnceHoweverOftenHasNextAsksAndNothingFollowsIt() {
    var reader =
        new SseReader(
            stream("data: stop\n\n"), NEVER_IDLE, new EchoParser(), ProviderException::new);

    assertTrue(reader.hasNext());
    assertTrue(reader.hasNext());
    assertInstanceOf(StreamEvent.Done.class, reader.next());
    assertFalse(reader.hasNext());
    assertThrows(NoSuchElementException.class, reader::next);
  }

  @Test
  void aClosedReaderHasNoMoreEventsAndHasClosedTheBody() {
    var feed = new FeedableInputStream();
    feed.feed("data: one\n\ndata: two\n\n");
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    assertTrue(reader.hasNext());

    reader.close();

    assertTrue(body.closed);
    assertFalse(reader.hasNext());
    assertThrows(NoSuchElementException.class, reader::next);
  }

  @Test
  void closingTheReaderReleasesALineReadBlockedOnTheBodyAsOneError() {
    var feed = new FeedableInputStream();
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    try (var consumer = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocked = consumer.submit(reader::next);
      feed.awaitBlockedRead();

      reader.close();

      var error =
          assertInstanceOf(StreamEvent.Error.class, Await.value("the blocked next", blocked));
      assertEquals("Stream read error", error.message());
      assertInstanceOf(IOException.class, error.cause());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aReaderClosedWhileItsParserReadsAnEventYieldsThatEventAndNothingAfter() {
    var body = new Closing(stream("data: silent\n\ndata: two\n\n"));
    var readers = new ArrayList<SseReader>();
    var closingParser =
        new EchoParser() {
          @Override
          public StreamEvent parse(String payload) {
            readers.getFirst().close();
            return super.parse(payload);
          }
        };
    var reader = new SseReader(body, NEVER_IDLE, closingParser, ProviderException::new);
    readers.add(reader);

    assertEquals(new StreamEvent.KeepAlive(), reader.next());
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void aReaderClosedBetweenTheLinesOfAnEventEndsInOneErrorInsteadOfThrowing() {
    var readers = new ArrayList<SseReader>();
    var firstLine = "data: one\n".getBytes(StandardCharsets.UTF_8);
    var body =
        new InputStream() {
          private boolean served;

          @Override
          public int read() {
            throw new UnsupportedOperationException("read in bulk");
          }

          @Override
          public int read(byte[] target, int offset, int length) {
            if (served) {
              return -1;
            }
            served = true;
            System.arraycopy(firstLine, 0, target, offset, firstLine.length);
            readers.getFirst().close();
            return firstLine.length;
          }
        };
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    readers.add(reader);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream read error", error.message());
    assertEquals("Stream closed", error.cause().getMessage());
    assertFalse(reader.hasNext());
  }

  @Test
  void closeReleasesAReadThatIgnoresInterruptsByClosingTheBodyFirst() {
    var body = new ClosedOnlyByClose();
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    var threads = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var blocked = threads.submit(reader::next);
      Await.latch("a read to block on the body", body.reading);

      Await.value("the close", threads.submit(reader::close));

      var error =
          assertInstanceOf(StreamEvent.Error.class, Await.value("the blocked next", blocked));
      assertEquals("body closed", error.cause().getMessage());
    } finally {
      threads.shutdownNow();
    }
  }

  @Test
  void aLineThatDoesNotArriveInTimeFailsWithTheProvidersException() {
    var body = new Closing(new FeedableInputStream());
    try (var reader =
        new SseReader(
            body,
            Duration.ofMillis(50),
            new EchoParser(),
            (message, status, cause) -> new ProviderException("acme: " + message, status, cause))) {

      var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

      assertEquals(FinishReason.ERROR, error.partial().finishReason());
      assertTrue(body.closed);
      assertEquals("acme: Stream idle timeout: no data received for 50 ms", error.message());
      assertEquals(
          error.message(), assertInstanceOf(ProviderException.class, error.cause()).getMessage());
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void aReadFailureIsATerminalStreamReadError() {
    var failure = new IOException("connection reset");
    var body = new Closing(failing(failure));
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream read error", error.message());
    assertSame(failure, error.cause());
    assertEquals(FinishReason.ERROR, error.partial().finishReason());
    assertTrue(body.closed);
    assertFalse(reader.hasNext());
  }

  @Test
  void anUncheckedReadFailureIsWrappedAsAReadFailure() {
    var failure = new IllegalStateException("decoder broke");
    var reader =
        new SseReader(failing(failure), NEVER_IDLE, new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    var cause = assertInstanceOf(IOException.class, error.cause());
    assertEquals("Stream read failed", cause.getMessage());
    assertSame(failure, cause.getCause());
  }

  @Test
  void anInterruptWhileWaitingForALineThrowsCancellationAndStaysSet() {
    var body = new Closing(new FeedableInputStream());
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      Thread.currentThread().interrupt();

      assertThrows(CancellationException.class, reader::next);

      assertTrue(Thread.interrupted());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void anInterruptAfterAnEventWasReadAheadClosesTheReaderAndThrowsCancellationAndStaysSet() {
    var feed = new FeedableInputStream();
    feed.feed("data: one\n\n");
    var body = new Closing(feed);
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      assertTrue(reader.hasNext());
      Thread.currentThread().interrupt();

      assertThrows(CancellationException.class, reader::next);

      assertTrue(Thread.interrupted());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void anInterruptOnceTheReaderEndedLeavesItEnded() {
    var body = new ByteArrayInputStream("data: stop\n\n".getBytes(StandardCharsets.UTF_8));
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      assertInstanceOf(StreamEvent.Done.class, reader.next());
      Thread.currentThread().interrupt();

      assertFalse(reader.hasNext());

      assertTrue(Thread.interrupted());
    }
  }

  private static List<StreamEvent> drain(SseReader reader) {
    var events = new ArrayList<StreamEvent>();
    while (reader.hasNext()) {
      events.add(reader.next());
    }
    return events;
  }

  private static InputStream byteByByte(String text) {
    var bytes = stream(text);
    return new InputStream() {
      @Override
      public int read() throws IOException {
        return bytes.read();
      }

      @Override
      public int read(byte[] target, int offset, int length) throws IOException {
        return length == 0 ? 0 : bytes.read(target, offset, 1);
      }
    };
  }

  private static InputStream endless(byte[] prefix, byte repeated) {
    return new InputStream() {
      private int position;

      @Override
      public int read() {
        return position < prefix.length ? prefix[position++] : repeated;
      }
    };
  }

  private static InputStream failing(Exception failure) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        if (failure instanceof IOException io) {
          throw io;
        }
        throw (RuntimeException) failure;
      }
    };
  }

  @Test
  void anInterruptOfAHasNextBlockedOnALineThrowsCancellationClosesTheBodyAndStaysSet() {
    var feed = new FeedableInputStream();
    var body = new Closing(feed);
    var interruptKept = new AtomicBoolean();
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      var read =
          new FutureTask<>(
              () -> {
                try {
                  return reader.hasNext();
                } finally {
                  interruptKept.set(Thread.currentThread().isInterrupted());
                }
              });
      var thread = Thread.ofVirtual().start(read);
      feed.awaitBlockedRead();

      thread.interrupt();

      assertInstanceOf(CancellationException.class, Await.failure("the blocked read", read));
      assertTrue(interruptKept.get());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void anInterruptAfterTheTerminalWasReadAheadThrowsCancellationInsteadOfTheTerminal() {
    var body = new Closing(stream("data: stop\n\n"));
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      assertTrue(reader.hasNext());
      Thread.currentThread().interrupt();

      assertThrows(CancellationException.class, reader::next);

      assertTrue(Thread.interrupted());
      assertTrue(body.closed);
      assertFalse(reader.hasNext());
    }
  }

  @Test
  void theConsumingThreadReadsEveryLineAndOneWatchdogWatchesTheStreamUntilItEnds() {
    var before = watchdogs();
    var body = new Closing(byteByByte("data: x\n\n".repeat(149) + "data: stop\n\n"));
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    assertTrue(reader.hasNext());
    var started = new HashSet<>(watchdogs());
    started.removeAll(before);
    assertEquals(150, drain(reader).size());

    assertEquals(1, started.size());
    assertEquals(Set.of(Thread.currentThread()), body.readers);
    awaitNoWatchdogBut(before);
  }

  @Test
  void aReaderClosedBeforeItsFirstLineStartsNoWatchdogAndNeverReadsTheBody() {
    var before = watchdogs();
    var body = new Closing(stream("data: stop\n\n"));
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    var afterBuilding = watchdogs();

    reader.close();

    assertTrue(before.containsAll(afterBuilding));
    assertFalse(reader.hasNext());
    assertTrue(body.closed);
    assertEquals(Set.of(), body.readers);
    assertTrue(before.containsAll(watchdogs()));
  }

  @ParameterizedTest
  @MethodSource("streamEndings")
  void theWatchdogEndsAndTheBodyIsClosedOnceTheStreamEnds(InputStream content) {
    var before = watchdogs();
    var body = new Closing(content);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    drain(reader);

    assertTrue(body.closed);
    awaitNoWatchdogBut(before);
  }

  static List<Named<InputStream>> streamEndings() {
    var unread = "data: never\n\n";
    return List.of(
        Named.of("done", stream("data: stop\n\n" + unread)),
        Named.of("parser error", stream("data: fail\n\n" + unread)),
        Named.of("body ended early", stream("data: one\n\n")),
        Named.of("oversized line", endless("data: ".getBytes(StandardCharsets.UTF_8), (byte) 'x')));
  }

  @Test
  void aBodyThatFailsAfterTwoEventsYieldsBothAndThenTheOneError() {
    var before = watchdogs();
    var feed = new FeedableInputStream();
    feed.feed("data: one\n\ndata: two\n\n");
    var failure = new IOException("connection reset");
    feed.fail(failure);
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);

    var events = drain(reader);

    assertEquals(
        List.of(new StreamEvent.TextDelta("one"), new StreamEvent.TextDelta("two")),
        events.subList(0, 2));
    assertSame(failure, assertInstanceOf(StreamEvent.Error.class, events.get(2)).cause());
    assertEquals(3, events.size());
    assertTrue(body.closed);
    awaitNoWatchdogBut(before);
  }

  @Test
  void aStalledBodyIsClosedByTheWatchdogAndEndsInTheIdleTimeoutWithThePartial() {
    var before = watchdogs();
    var body = new Closing(new FeedableInputStream());
    var parser =
        new EchoParser() {
          @Override
          public Response<Void> partial() {
            return Response.newBuilder()
                .withContent("so far")
                .withFinishReason(FinishReason.ERROR)
                .build();
          }
        };
    var reader = new SseReader(body, Duration.ofMillis(1), parser, ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, consume(reader::next));

    assertEquals("Stream idle timeout: no data received for 1 ms", error.message());
    assertInstanceOf(ProviderException.class, error.cause());
    assertEquals("so far", error.partial().content());
    assertEquals("helios-sse-idle", body.closers.getFirst().getName());
    assertFalse(reader.hasNext());
    awaitNoWatchdogBut(before);
  }

  @Test
  void timeTheConsumerSpendsBetweenLinesAlreadyReadIsNeverIdle() {
    var timeout = Duration.ofSeconds(2);
    var body = new Closing(stream("data: one\n\ndata: two\n\n"));
    try (var reader = new SseReader(body, timeout, new EchoParser(), ProviderException::new)) {
      assertEquals(new StreamEvent.TextDelta("one"), reader.next());

      atLeast(timeout);

      assertEquals(new StreamEvent.TextDelta("two"), reader.next());
      assertFalse(body.closed);
    }
  }

  @Test
  void linesThatEachArriveWithinTheTimeoutNeverTimeOutHoweverLongTheStreamLasts() {
    var feed = new FeedableInputStream();
    var reader =
        new SseReader(feed, Duration.ofSeconds(2), new EchoParser(), ProviderException::new);
    var consumer = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var events = consumer.submit(() -> drain(reader));

      for (var i = 0; i < 10; i++) {
        atLeast(Duration.ofMillis(250));
        feed.feed("data: " + i + "\n\n");
      }
      feed.feed("data: stop\n\n");

      var all = Await.value("the stream's events", events);
      assertEquals(11, all.size());
      assertInstanceOf(StreamEvent.Done.class, all.getLast());
    } finally {
      consumer.shutdownNow();
    }
  }

  @Test
  void aWaitTheWatchdogClaimsFirstFailsAsIdleEvenWhenItsLineArrives() {
    var feed = new FeedableInputStream();
    var body = new Closing(feed);
    var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new);
    var consumer = Executors.newVirtualThreadPerTaskExecutor();
    try {
      var next = consumer.submit(reader::next);
      feed.awaitBlockedRead();

      assertTrue(reader.wait.compareAndSet(reader.wait.get(), SseReader.CLAIMED));
      feed.feed("data: one\n\n");

      var error = assertInstanceOf(StreamEvent.Error.class, Await.value("the next event", next));
      assertEquals(
          "Stream idle timeout: no data received for " + NEVER_IDLE.toMillis() + " ms",
          error.message());
      assertTrue(body.closed);
    } finally {
      consumer.shutdownNow();
    }
  }

  @Test
  void aWaitWhoseLineArrivesFirstCannotBeClaimedAndKeepsTheBodyOpen() {
    var feed = new FeedableInputStream();
    var body = new Closing(feed);
    try (var reader = new SseReader(body, NEVER_IDLE, new EchoParser(), ProviderException::new)) {
      var consumer = Executors.newVirtualThreadPerTaskExecutor();
      try {
        var next = consumer.submit(reader::next);
        feed.awaitBlockedRead();
        var mark = reader.wait.get();

        feed.feed("data: one\n\n");

        assertEquals(new StreamEvent.TextDelta("one"), Await.value("the next event", next));
        assertFalse(reader.wait.compareAndSet(mark, SseReader.CLAIMED));
        assertEquals(SseReader.NOT_WAITING, reader.wait.get());
        assertFalse(body.closed);
      } finally {
        consumer.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @MethodSource("timeoutsBeyondTheNanosecondRange")
  void anIdleTimeoutBeyondTheNanosecondRangeReadsTheStreamWhileItsWatchdogSleeps(Duration timeout) {
    var before = watchdogs();
    var reader =
        new SseReader(
            stream("data: one\n\ndata: stop\n\n"),
            timeout,
            new EchoParser(),
            ProviderException::new);
    assertEquals(new StreamEvent.TextDelta("one"), reader.next());
    var started = new HashSet<>(watchdogs());
    started.removeAll(before);
    assertEquals(1, started.size());
    var watchdog = started.iterator().next();

    Await.until(
        "the watchdog to sleep", () -> "TIMED_WAITING".equals(watchdogStates().get(watchdog)));

    assertInstanceOf(StreamEvent.Done.class, reader.next());
    awaitNoWatchdogBut(before);
  }

  static List<Duration> timeoutsBeyondTheNanosecondRange() {
    return List.of(Duration.ofDays(365_000_000), ChronoUnit.FOREVER.getDuration());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void anIdleTimeoutThatIsNotPositiveFailsTheFirstLineAtOnceWithoutAWatchdog(long millis) {
    var before = watchdogs();
    var body = new Closing(stream("data: one\n\n"));
    var reader =
        new SseReader(body, Duration.ofMillis(millis), new EchoParser(), ProviderException::new);

    var error = assertInstanceOf(StreamEvent.Error.class, reader.next());

    assertEquals("Stream idle timeout: no data received for " + millis + " ms", error.message());
    assertTrue(body.closed);
    assertEquals(Set.of(), body.readers);
    assertTrue(before.containsAll(watchdogs()));
    assertFalse(reader.hasNext());
  }

  /** Returns once at least {@code timeout} has passed: a stalled stream with it has timed out. */
  private static void atLeast(Duration timeout) {
    try (var stalled =
        new SseReader(
            new FeedableInputStream(), timeout, new EchoParser(), ProviderException::new)) {
      assertInstanceOf(StreamEvent.Error.class, stalled.next());
    }
  }

  /** What {@code consumer} returns on a thread of its own, interrupted if it is still waiting. */
  private static <T> T consume(Callable<T> consumer) {
    var thread = Executors.newVirtualThreadPerTaskExecutor();
    try {
      return Await.value("the consumer", thread.submit(consumer));
    } finally {
      thread.shutdownNow();
    }
  }
}

/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.RecordingSubscriber;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IteratorPublisherTest {

  private final CancellationToken token = new CancellationToken();
  private final AtomicInteger opened = new AtomicInteger();

  @Test
  void nothingIsOpenedBeforeTheFirstRequest() {
    var source = new Source(List.of("a"));
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(source).subscribe(subscriber);
    subscriber.subscription();
    var openedBeforeRequest = opened.get();
    subscriber.request(1);

    assertEquals(0, openedBeforeRequest);
    assertEquals("a", subscriber.next());
    assertEquals(List.of("a"), subscriber.awaitCompletion());
    assertEquals(1, opened.get());
    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);
  }

  @Test
  void itemsArriveInOrderNeverBeyondDemandAndTheSourceIsClosedAtTheEnd() {
    var source = new Source(List.of("a", "b", "c"));
    var subscriber = new RecordingSubscriber<String>(1);

    publisherOf(source).subscribe(subscriber);
    for (var expected : List.of("a", "b", "c")) {
      assertEquals(expected, subscriber.next());
      subscriber.request(1);
    }

    assertEquals(List.of("a", "b", "c"), subscriber.awaitCompletion());
    assertEquals(0, subscriber.violations());
    Await.latch("the source closed", source.closed);
  }

  @Test
  void theStreamCompletesWithoutFurtherDemandOnceTheSourceEnds() {
    var subscriber = new RecordingSubscriber<String>(2);

    publisherOf(new Source(List.of("a", "b"))).subscribe(subscriber);

    assertEquals(List.of("a", "b"), subscriber.awaitCompletion());
  }

  @Test
  void demandBeyondTheLargestLongSaturates() {
    var subscriber = new RecordingSubscriber<String>(Long.MAX_VALUE);

    publisherOf(new Source(List.of("a"))).subscribe(subscriber);
    subscriber.subscription().request(Long.MAX_VALUE);

    assertEquals(List.of("a"), subscriber.awaitCompletion());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void aSecondSubscriberIsRefusedAndTheFirstIsServed() {
    var publisher = publisherOf(new Source(List.of("a")));
    var first = new RecordingSubscriber<String>();
    var second = new RecordingSubscriber<String>();

    publisher.subscribe(first);
    publisher.subscribe(second);
    second.subscription().request(1);
    second.subscription().cancel();

    assertInstanceOf(IllegalStateException.class, second.awaitFailure());
    assertEquals(List.of("a"), first.awaitCompletion());
    assertEquals(1, opened.get());
  }

  @Test
  void aNullSubscriberIsRejected() {
    var publisher = publisherOf(new Source(List.of()));

    assertThrows(NullPointerException.class, () -> publisher.subscribe(null));
  }

  @Test
  void aRequestInsideOnSubscribeIsServedOnlyAfterOnSubscribeReturns() {
    var source = new Source(List.of("a"));
    var returned = new AtomicBoolean();
    var overlaps = new AtomicInteger();
    var completed = new CountDownLatch(1);
    var subscriber =
        new Flow.Subscriber<String>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(1);
            returned.set(true);
          }

          @Override
          public void onNext(String item) {
            countOverlap();
          }

          @Override
          public void onError(Throwable failure) {
            countOverlap();
            completed.countDown();
          }

          @Override
          public void onComplete() {
            countOverlap();
            completed.countDown();
          }

          private void countOverlap() {
            if (!returned.get()) {
              overlaps.incrementAndGet();
            }
          }
        };

    publisherOf(source).subscribe(subscriber);

    Await.latch("the stream completed", completed);
    assertEquals(0, overlaps.get());
  }

  @Test
  void anOnSubscribeThatThrowsCancelsTheSubscriptionItRequestedOn() {
    var broken = new IllegalStateException("broken subscriber");
    var subscription = new AtomicReference<Flow.Subscription>();
    var subscriber =
        new Flow.Subscriber<String>() {
          @Override
          public void onSubscribe(Flow.Subscription granted) {
            subscription.set(granted);
            granted.request(1);
            throw broken;
          }

          @Override
          public void onNext(String item) {}

          @Override
          public void onError(Throwable failure) {}

          @Override
          public void onComplete() {}
        };
    var source = new Source(List.of("a"));
    var publisher = publisherOf(source);

    assertSame(
        broken, assertThrows(IllegalStateException.class, () -> publisher.subscribe(subscriber)));
    subscription.get().request(1);

    assertEquals(0, token.activeCallbackCountForTests());
    assertEquals(0, opened.get());
  }

  @Test
  void aNonPositiveFirstRequestFailsTheStreamWithoutOpeningIt() {
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(new Source(List.of("a"))).subscribe(subscriber);
    subscriber.subscription().request(0);

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    assertEquals(0, opened.get());
  }

  @Test
  void aNonPositiveRequestMidStreamFailsTheStreamAndClosesTheSource() {
    var source = new Source(List.of("a", "b"));
    var subscriber = new RecordingSubscriber<String>(1);

    publisherOf(source).subscribe(subscriber);
    assertEquals("a", subscriber.next());
    subscriber.subscription().request(-1);
    subscriber.subscription().request(1);

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    Await.latch("the source closed", source.closed);
    assertEquals(List.of("a"), subscriber.items());
  }

  @Test
  void aNonPositiveRequestClosesABlockedSourceAndFailsTheStream() {
    var subscriber = new RecordingSubscriber<String>(0);
    var source = new Source(List.of());
    source.blockAt = 0;

    publisherOf(source).subscribe(subscriber);
    subscriber.subscription().request(1);
    Await.latch("the pull blocked", source.blocked);
    subscriber.subscription().request(0);

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    Await.latch("the source closed", source.closed);
  }

  @Test
  void aNonPositiveRequestInterruptsAPullThatIgnoresClose() {
    var subscriber = new RecordingSubscriber<String>(0);
    var source = new Source(List.of());
    source.blockAt = 0;
    source.ignoresClose = true;

    publisherOf(source).subscribe(subscriber);
    subscriber.subscription().request(1);
    Await.latch("the pull blocked", source.blocked);
    subscriber.subscription().request(0);

    Await.latch("the owned thread interrupted", source.interrupted);
    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
  }

  @Test
  void aNonPositiveRequestInsideOnNextFailsTheStreamWithoutAnotherPull() {
    var source = new Source(List.of("a"));
    source.blockAt = 1;
    source.ignoresClose = true;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source)
        .subscribe(
            onEachItem(
                subscriber,
                () -> {
                  subscriber.subscription().request(0);
                  subscriber.subscription().request(1);
                }));

    assertInstanceOf(IllegalArgumentException.class, subscriber.awaitFailure());
    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);
    assertEquals(1, source.blocked.getCount());
    assertEquals(List.of("a"), subscriber.items());
  }

  @Test
  void aCancelAfterANonPositiveRequestInsideOnNextEndsTheSignals() {
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(new Source(List.of("a", "b")))
        .subscribe(
            onEachItem(
                subscriber,
                () -> {
                  subscriber.subscription().request(0);
                  subscriber.cancel();
                }));
    assertEquals("a", subscriber.next());

    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);
    assertFalse(subscriber.ended());
  }

  @Test
  void anInterruptTheSubscriberLeavesSetFailsAStreamWaitingForDemand() {
    var subscriber = new RecordingSubscriber<String>(1);

    publisherOf(new Source(List.of("a", "b")))
        .subscribe(onEachItem(subscriber, () -> Thread.currentThread().interrupt()));

    assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    assertEquals(List.of("a"), subscriber.items());
  }

  @Test
  void cancellingInsideOnNextEndsTheSignalsWithoutAnotherPull() {
    var source = new Source(List.of("a"));
    source.blockAt = 1;
    source.ignoresClose = true;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(onEachItem(subscriber, subscriber::cancel));
    assertEquals("a", subscriber.next());

    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);
    assertEquals(1, source.blocked.getCount());
    assertFalse(subscriber.ended());
  }

  @Test
  void cancellingTheSubscriptionClosesABlockedSourceAndEndsTheSignals() {
    var source = new Source(List.of("a"));
    source.blockAt = 1;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(subscriber);
    assertEquals("a", subscriber.next());
    Await.latch("the pull blocked", source.blocked);
    subscriber.cancel();
    Await.latch("the source closed", source.closed);
    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);
    subscriber.cancel();
    subscriber.subscription().request(1);

    assertFalse(subscriber.ended());
    assertEquals(0, subscriber.violations());
  }

  @Test
  void cancellingInterruptsAPullThatIgnoresClose() {
    var source = new Source(List.of());
    source.blockAt = 0;
    source.ignoresClose = true;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(subscriber);
    Await.latch("the pull blocked", source.blocked);
    subscriber.cancel();

    Await.latch("the owned thread interrupted", source.interrupted);
  }

  @Test
  void cancellingInterruptsALaterPullThatIgnoresClose() {
    var source = new Source(List.of("a"));
    source.blockAt = 1;
    source.ignoresClose = true;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(subscriber);
    assertEquals("a", subscriber.next());
    Await.latch("the second pull blocked", source.blocked);
    subscriber.cancel();

    Await.latch("the owned thread interrupted", source.interrupted);
  }

  @Test
  void cancellingWhileWaitingForDemandEndsTheSignals() {
    var source = new Source(List.of("a", "b"));
    var subscriber = new RecordingSubscriber<String>(1);

    publisherOf(source).subscribe(subscriber);
    assertEquals("a", subscriber.next());
    Await.latch("the next item pulled", source.pulled(2));
    subscriber.cancel();
    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);

    assertEquals(List.of("a"), subscriber.items());
    assertFalse(subscriber.ended());
  }

  @Test
  void aSubscriptionCancelledInsideOnSubscribeLeavesNothingWatchingTheToken() {
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(new Source(List.of("a")))
        .subscribe(
            onSubscribe(
                subscriber,
                subscription -> {
                  subscription.request(1);
                  subscription.cancel();
                }));

    assertEquals(0, token.activeCallbackCountForTests());
    assertEquals(0, opened.get());
  }

  @Test
  void aSubscriptionCancelledInsideOnSubscribeHearsNothingOfATokenCancelledBefore() {
    token.cancel("too late");
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(new Source(List.of("a")))
        .subscribe(onSubscribe(subscriber, Flow.Subscription::cancel));

    assertFalse(subscriber.ended());
    assertEquals(0, opened.get());
  }

  @Test
  void aRequestAfterCancellationOpensNothing() {
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(new Source(List.of("a"))).subscribe(subscriber);
    subscriber.cancel();
    subscriber.subscription().request(1);

    assertEquals(0, opened.get());
    assertFalse(subscriber.ended());
    assertEquals(0, token.activeCallbackCountForTests());
  }

  @Test
  void cancellingTheTokenLeavesTheSubscribersOwnWaitUninterrupted() {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CompletableFuture<Boolean>();
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(new Source(List.of("a")))
        .subscribe(
            onEachItem(
                subscriber,
                () -> {
                  entered.countDown();
                  try {
                    release.await();
                    interrupted.complete(false);
                  } catch (InterruptedException e) {
                    interrupted.complete(true);
                  }
                }));
    Await.latch("onNext entered", entered);
    token.cancel("user stopped");
    release.countDown();

    assertFalse(Await.value("the subscriber's own wait", interrupted));
  }

  @Test
  void aCancelledStreamsErrorArrivesWithoutTheInterruptThatEndedThePull() {
    var source = new Source(List.of());
    source.blockAt = 0;
    source.ignoresClose = true;
    source.restoresInterrupt = true;
    var interruptedAtError = new CompletableFuture<Boolean>();
    var subscriber =
        new Flow.Subscriber<String>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(String item) {}

          @Override
          public void onError(Throwable failure) {
            interruptedAtError.complete(Thread.currentThread().isInterrupted());
          }

          @Override
          public void onComplete() {}
        };

    publisherOf(source).subscribe(subscriber);
    Await.latch("the pull blocked", source.blocked);
    token.cancel("user stopped");

    assertFalse(Await.value("onError", interruptedAtError));
  }

  @Test
  void cancellingTheTokenEndsTheStreamWithItsReasonAndClosesTheSource() {
    var source = new Source(List.of());
    source.blockAt = 0;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(subscriber);
    Await.latch("the pull blocked", source.blocked);
    token.cancel("user stopped");

    var failure = assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    assertEquals("user stopped", failure.getMessage());
    Await.latch("the source closed", source.closed);
  }

  @Test
  void aTokenCancelledBeforeTheFirstRequestFailsItWithoutOpening() {
    token.cancel("too late");
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(new Source(List.of("a"))).subscribe(subscriber);

    assertEquals("too late", subscriber.awaitFailure().getMessage());
    assertEquals(0, opened.get());
  }

  @Test
  void aTokenCancelledBeforeSubscribingFailsASubscriberThatNeverRequests() {
    token.cancel("too late");
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(new Source(List.of("a"))).subscribe(subscriber);

    var failure = assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    assertEquals("too late", failure.getMessage());
    assertEquals(0, opened.get());
  }

  @Test
  void aTokenCancelledAfterSubscribingFailsASubscriberThatNeverRequests() {
    var subscriber = new RecordingSubscriber<String>(0);

    publisherOf(new Source(List.of("a"))).subscribe(subscriber);
    subscriber.subscription();
    token.cancel("user stopped");

    var failure = assertInstanceOf(CancellationException.class, subscriber.awaitFailure());
    assertEquals("user stopped", failure.getMessage());
    assertEquals(0, opened.get());
    assertEquals(0, token.activeCallbackCountForTests());
  }

  @Test
  void aTokenCancelledWhileOpeningFailsTheStreamAndClosesWhatWasOpened() {
    var source = new Source(List.of("a"));
    var subscriber = new RecordingSubscriber<String>();

    new IteratorPublisher<String>(
            () -> {
              token.cancel("cancelled while opening");
              return source;
            },
            token)
        .subscribe(subscriber);

    assertEquals("cancelled while opening", subscriber.awaitFailure().getMessage());
    Await.latch("the source closed", source.closed);
  }

  @Test
  void aFailingSourceEndsTheStreamWithItsFailureAndIsClosed() {
    var broken = new IllegalStateException("broken pipe");
    var source = new Source(List.of("a"));
    source.failure = broken;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(subscriber);

    assertSame(broken, subscriber.awaitFailure());
    Await.latch("the source closed", source.closed);
  }

  @Test
  void aFailingOpenerEndsTheStreamWithItsFailure() {
    var refused = new IllegalStateException("refused");
    var subscriber = new RecordingSubscriber<String>();

    new IteratorPublisher<String>(
            () -> {
              throw refused;
            },
            token)
        .subscribe(subscriber);

    assertSame(refused, subscriber.awaitFailure());
  }

  @Test
  void anErrorFromTheOpenerEndsTheStreamWithIt() {
    var broken = new AssertionError("linkage broke");
    var subscriber = new RecordingSubscriber<String>();

    new IteratorPublisher<String>(
            () -> {
              throw broken;
            },
            token)
        .subscribe(subscriber);

    assertSame(broken, subscriber.awaitFailure());
    Await.until("the owned thread finished", () -> token.activeCallbackCountForTests() == 0);
  }

  @Test
  void anErrorFromTheSourceEndsTheStreamWithItAndClosesTheSource() {
    var overflow = new StackOverflowError("deep");
    var source = new Source(List.of("a"));
    source.failure = overflow;
    var subscriber = new RecordingSubscriber<String>();

    publisherOf(source).subscribe(subscriber);

    assertSame(overflow, subscriber.awaitFailure());
    Await.latch("the source closed", source.closed);
  }

  @Test
  void nullArgumentsAreRejected() {
    assertThrows(NullPointerException.class, () -> new IteratorPublisher<String>(null, token));
    assertThrows(
        NullPointerException.class,
        () -> new IteratorPublisher<>(() -> new Source(List.of()), null));
  }

  /** Forwards every signal to {@code recorder}, running {@code action} after each item. */
  private static Flow.Subscriber<String> onEachItem(
      RecordingSubscriber<String> recorder, Runnable action) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        recorder.onSubscribe(subscription);
      }

      @Override
      public void onNext(String item) {
        recorder.onNext(item);
        action.run();
      }

      @Override
      public void onError(Throwable failure) {
        recorder.onError(failure);
      }

      @Override
      public void onComplete() {
        recorder.onComplete();
      }
    };
  }

  /**
   * Forwards every signal to {@code recorder}, running {@code action} on the subscription first.
   */
  private static Flow.Subscriber<String> onSubscribe(
      RecordingSubscriber<String> recorder, Consumer<Flow.Subscription> action) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        action.accept(subscription);
        recorder.onSubscribe(subscription);
      }

      @Override
      public void onNext(String item) {
        recorder.onNext(item);
      }

      @Override
      public void onError(Throwable failure) {
        recorder.onError(failure);
      }

      @Override
      public void onComplete() {
        recorder.onComplete();
      }
    };
  }

  private IteratorPublisher<String> publisherOf(Source source) {
    return new IteratorPublisher<>(
        () -> {
          opened.incrementAndGet();
          return source;
        },
        token);
  }

  /**
   * A source of fixed items that can block a pull until it is closed, or interrupted when it
   * ignores close (then ending with the interrupt restored, when asked), and can fail its last
   * pull.
   */
  private static final class Source implements CloseableIterator<String> {

    private final List<String> items;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch unblock = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final List<CountDownLatch> pulls =
        List.of(new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
    private int next;
    private int blockAt = -1;
    private boolean ignoresClose;
    private boolean restoresInterrupt;
    private Throwable failure;

    Source(List<String> items) {
      this.items = items;
    }

    CountDownLatch pulled(int count) {
      return pulls.get(count - 1);
    }

    @Override
    public boolean hasNext() {
      if (next == blockAt) {
        blocked.countDown();
        try {
          if (ignoresClose) {
            new CountDownLatch(1).await();
          }
          unblock.await();
        } catch (InterruptedException e) {
          interrupted.countDown();
          if (restoresInterrupt) {
            Thread.currentThread().interrupt();
            return false;
          }
          throw new IllegalStateException("interrupted", e);
        }
      }
      if (failure instanceof RuntimeException runtime && next == items.size()) {
        throw runtime;
      }
      if (failure instanceof Error error && next == items.size()) {
        throw error;
      }
      return next < items.size();
    }

    @Override
    public String next() {
      pulls.get(next).countDown();
      return items.get(next++);
    }

    @Override
    public void close() {
      closed.countDown();
      if (!ignoresClose) {
        unblock.countDown();
      }
    }
  }
}

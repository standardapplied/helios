/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class IdsTest {

  // ---------------------------------------------------------------------------
  // nextState — deterministic tests covering all three branches
  // ---------------------------------------------------------------------------

  @Test
  void nextStateResetsOnNewTimestamp() {
    var state = Ids.nextState(0, 1000);

    assertEquals(1000, state >>> 12);
    assertEquals(0, state & 0xFFF);
  }

  @Test
  void nextStateResetsCounterWhenTimestampAdvances() {
    var initial = (1000L << 12) | 42;
    var state = Ids.nextState(initial, 2000);

    assertEquals(2000, state >>> 12);
    assertEquals(0, state & 0xFFF);
  }

  @Test
  void nextStateIncrementsCounter() {
    var initial = (1000L << 12) | 5;
    var state = Ids.nextState(initial, 1000);

    assertEquals(1000, state >>> 12);
    assertEquals(6, state & 0xFFF);
  }

  @Test
  void nextStateIncrementsCounterFromZero() {
    var initial = 1000L << 12;
    var state = Ids.nextState(initial, 1000);

    assertEquals(1000, state >>> 12);
    assertEquals(1, state & 0xFFF);
  }

  @Test
  void nextStateAdvancesTimestampOnCounterOverflow() {
    var initial = (1000L << 12) | 0xFFF;
    var state = Ids.nextState(initial, 1000);

    assertEquals(1001, state >>> 12);
    assertEquals(0, state & 0xFFF);
  }

  @Test
  void nextStateHandlesClockDrift() {
    var initial = (1000L << 12) | 5;
    var state = Ids.nextState(initial, 999);

    assertEquals(1000, state >>> 12);
    assertEquals(6, state & 0xFFF);
  }

  @Test
  void nextStateOverflowThenResume() {
    var overflowed = Ids.nextState((1000L << 12) | 0xFFF, 1000);

    assertEquals(1001, overflowed >>> 12);
    assertEquals(0, overflowed & 0xFFF);

    var next = Ids.nextState(overflowed, 1000);

    assertEquals(1001, next >>> 12);
    assertEquals(1, next & 0xFFF);
  }

  @Test
  void nextStateMonotonicThroughFullCounterCycle() {
    var ts = 5000L;
    var state = ts << 12;

    for (int i = 0; i < 4096; i++) {
      var next = Ids.nextState(state, ts);
      assertTrue(next > state);
      state = next;
    }

    assertEquals(ts + 1, state >>> 12);
    assertEquals(0, state & 0xFFF);
  }

  @Test
  void nextStateGuaranteedGreaterThanInput() {
    var inputs =
        List.of(0L, 1000L << 12, (1000L << 12) | 1, (1000L << 12) | 0xFFE, (1000L << 12) | 0xFFF);

    for (var input : inputs) {
      var ts = input >>> 12;
      var result = Ids.nextState(input, ts);
      assertTrue(result > input, "nextState must return value > input for state " + input);
    }
  }

  // ---------------------------------------------------------------------------
  // newId — integration tests
  // ---------------------------------------------------------------------------

  @Test
  void newIdReturnsNonNull() {
    assertNotNull(Ids.newId());
  }

  @Test
  void newIdHasVersion7() {
    for (int i = 0; i < 100; i++) {
      assertEquals(7, Ids.newId().version());
    }
  }

  @Test
  void newIdHasRfc4122Variant() {
    for (int i = 0; i < 100; i++) {
      assertEquals(2, Ids.newId().variant());
    }
  }

  @Test
  void newIdIsUnique() {
    var ids = new HashSet<UUID>();
    for (int i = 0; i < 10_000; i++) {
      assertTrue(ids.add(Ids.newId()));
    }
  }

  @Test
  void newIdIsMonotonicallyIncreasing() {
    var previous = Ids.newId();
    for (int i = 0; i < 10_000; i++) {
      var current = Ids.newId();
      assertTrue(
          Long.compareUnsigned(current.getMostSignificantBits(), previous.getMostSignificantBits())
              > 0);
      previous = current;
    }
  }

  @Test
  void newIdTimestampIsReasonable() {
    var before = System.currentTimeMillis();
    var id = Ids.newId();
    var after = System.currentTimeMillis();

    var timestamp = id.getMostSignificantBits() >>> 16;

    assertTrue(timestamp >= before);
    assertTrue(timestamp <= after + 1000);
  }

  @Test
  void newIdStringFormat() {
    var id = Ids.newId();
    var str = id.toString();

    assertTrue(str.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
  }

  @Test
  void newIdHighThroughputUniquenessAndOrdering() {
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(Ids.newId());
    }

    assertEquals(10_000, new HashSet<>(ids).size());

    for (var id : ids) {
      assertEquals(7, id.version());
      assertEquals(2, id.variant());
    }

    for (int i = 1; i < ids.size(); i++) {
      assertTrue(
          Long.compareUnsigned(
                  ids.get(i).getMostSignificantBits(), ids.get(i - 1).getMostSignificantBits())
              > 0);
    }
  }

  @Test
  void newIdConcurrentUniqueness() throws Exception {
    var threadCount = 8;
    var idsPerThread = 5_000;
    var executor = Executors.newFixedThreadPool(threadCount);
    var latch = new CountDownLatch(1);

    var futures = new ArrayList<Future<List<UUID>>>();
    for (int t = 0; t < threadCount; t++) {
      futures.add(
          executor.submit(
              () -> {
                latch.await();
                var ids = new ArrayList<UUID>(idsPerThread);
                for (int i = 0; i < idsPerThread; i++) {
                  ids.add(Ids.newId());
                }
                return ids;
              }));
    }

    latch.countDown();

    var allIds = new HashSet<UUID>();
    for (var future : futures) {
      for (var id : future.get(10, TimeUnit.SECONDS)) {
        assertTrue(allIds.add(id));
      }
    }

    assertEquals(threadCount * idsPerThread, allIds.size());
    executor.shutdown();
  }

  @Test
  void newIdConcurrentMonotonicity() throws Exception {
    var threadCount = 8;
    var idsPerThread = 5_000;
    var executor = Executors.newFixedThreadPool(threadCount);
    var latch = new CountDownLatch(1);

    var futures = new ArrayList<Future<List<UUID>>>();
    for (int t = 0; t < threadCount; t++) {
      futures.add(
          executor.submit(
              () -> {
                latch.await();
                var ids = new ArrayList<UUID>(idsPerThread);
                for (int i = 0; i < idsPerThread; i++) {
                  ids.add(Ids.newId());
                }
                return ids;
              }));
    }

    latch.countDown();

    var allIds = new ArrayList<UUID>();
    for (var future : futures) {
      allIds.addAll(future.get(10, TimeUnit.SECONDS));
    }

    allIds.sort(
        (a, b) -> Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits()));

    for (int i = 1; i < allIds.size(); i++) {
      assertTrue(
          Long.compareUnsigned(
                  allIds.get(i).getMostSignificantBits(),
                  allIds.get(i - 1).getMostSignificantBits())
              > 0);
    }

    executor.shutdown();
  }

  // ---------------------------------------------------------------------------
  // now
  // ---------------------------------------------------------------------------

  @Test
  void nowReturnsUtc() {
    assertEquals(ZoneOffset.UTC, Ids.now().getOffset());
  }

  @Test
  void nowIsReasonable() {
    var before = OffsetDateTime.now(ZoneOffset.UTC);
    var now = Ids.now();
    var after = OffsetDateTime.now(ZoneOffset.UTC);

    assertFalse(now.isBefore(before));
    assertFalse(now.isAfter(after));
  }
}

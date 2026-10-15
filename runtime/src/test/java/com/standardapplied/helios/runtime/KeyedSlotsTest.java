/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A key holds slots up to its bound, and keeps no entry once every slot is given back. */
final class KeyedSlotsTest {

  @Test
  void aKeyTakesUpToItsBoundAndKeepsNoEntryOnceEverySlotIsBack() {
    var slots = new KeyedSlots<String>(2);

    var taken =
        List.of(
            slots.tryAcquire("closed"),
            slots.tryAcquire("closed"),
            slots.tryAcquire("closed"),
            slots.tryAcquire("open"));
    slots.release("closed");
    var retaken = slots.tryAcquire("closed");
    slots.release("closed");
    slots.release("closed");

    assertEquals(List.of(true, true, false, true), taken);
    assertTrue(retaken);
    assertEquals(1, slots.heldKeys());
  }
}

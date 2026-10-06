/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.MockModel;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class UsageAccountingTest {

  @Test
  void aBlankModelIdIsPricedAtTheMainModel() {
    var pricedIds = new CopyOnWriteArrayList<String>();
    var model = new MockModel("unused");
    var accounting =
        new UsageAccounting(
            model,
            (modelId, usage) -> {
              pricedIds.add(modelId);
              return CostEstimate.ofMicroUsd(4);
            });
    var state =
        new SessionState(
            "sess-accounting", new CancellationToken(), InstantSource.fixed(Instant.EPOCH));

    accounting.add(state, " ", Usage.of(1, 2));

    assertEquals(List.of(model.id()), pricedIds);
    assertEquals(Usage.of(1, 2), state.totals().usage());
    assertEquals(CostEstimate.ofMicroUsd(4), state.totals().cost());
  }
}

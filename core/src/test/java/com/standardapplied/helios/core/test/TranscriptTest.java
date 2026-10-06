/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranscriptTest {

  record Pair(String name, Map<String, ?> counts) {}

  @Test
  void rendersRecordComponentsByNameAndMapsInKeyOrder() {
    var rendered = new Transcript().render(new Pair("p", Map.of("b", 2, "a", 1, "c", 3)));

    assertEquals("Pair[name=p, counts={a=1, b=2, c=3}]", rendered);
  }

  @Test
  void rendersAStringHeldInAMapThroughTheCanonicalForm() {
    var transcript = new Transcript(String::toUpperCase);

    assertEquals("Pair[name=p, counts={k=V}]", transcript.render(new Pair("p", Map.of("k", "v"))));
  }

  @Test
  void rendersCollectionsAndNull() {
    assertEquals("[x, null, [1]]", new Transcript().render(Arrays.asList("x", null, List.of(1))));
  }

  @Test
  void rendersAnExceptionWithItsProviderStatusAndCauseChain() {
    var failure =
        new TransientStreamException(
            "dropped", new ProviderException("upstream", 503, new IOException("reset")), "acme");

    assertEquals(
        "TransientStreamException(dropped) provider=acme caused by ProviderException(upstream)"
            + " status=503 caused by IOException(reset)",
        new Transcript().render(failure));
  }

  @Test
  void eventsDrainsTheIteratorOneEventPerLineAndClosesIt() {
    var closed = new boolean[1];
    var events =
        List.<StreamEvent>of(new StreamEvent.TextDelta("hi"), new StreamEvent.TextDelta("!"));
    var iterator = events.iterator();
    var closeable =
        new CloseableIterator<StreamEvent>() {
          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public StreamEvent next() {
            return iterator.next();
          }

          @Override
          public void close() {
            closed[0] = true;
          }
        };

    assertEquals("TextDelta[text=hi]\nTextDelta[text=!]\n", new Transcript().events(closeable));
    assertTrue(closed[0]);
  }

  @Test
  void outcomeRendersTheValueOrTheThrownException() {
    assertEquals("ok\n", new Transcript().outcome(() -> "ok"));
    assertEquals(
        "threw IllegalStateException(no)\n",
        new Transcript()
            .outcome(
                () -> {
                  throw new IllegalStateException("no");
                }));
  }
}

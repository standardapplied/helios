/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** An origin tag is spelled in a message's metadata and read back from it, nothing else. */
final class MessageOriginTest {

  @Test
  void eachOriginIsSpelledLowerCaseWithHyphensUnderTheKey() {
    assertEquals(
        List.of(
            Map.of("helios.origin", "schema-correction"),
            Map.of("helios.origin", "rejected-attempt"),
            Map.of("helios.origin", "hook-inject"),
            Map.of("helios.origin", "partial-turn")),
        List.of(MessageOrigin.values()).stream().map(MessageOrigin::metadata).toList());
  }

  @Test
  void aTaggedMessageReadsBackItsOrigin() {
    for (var origin : MessageOrigin.values()) {
      assertEquals(Optional.of(origin), MessageOrigin.of(tagged(origin.metadata())));
    }
  }

  @Test
  void aMessageWithoutAKnownTagHasNoOrigin() {
    var withoutMetadata = new Message(Role.USER, "a", List.of(), null, null, null, List.of());

    assertEquals(
        List.of(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
        List.of(
            MessageOrigin.of(Message.user("a")),
            MessageOrigin.of(withoutMetadata),
            MessageOrigin.of(tagged(Map.of(MessageOrigin.KEY, "HOOK_INJECT"))),
            MessageOrigin.of(tagged(Map.of("other", "hook-inject")))));
  }

  @Test
  void aNullMessageIsRefused() {
    assertThrows(NullPointerException.class, () -> MessageOrigin.of(null));
  }

  private static Message tagged(Map<String, String> metadata) {
    return Message.newBuilder().withRole(Role.USER).withContent("a").withMetadata(metadata).build();
  }
}

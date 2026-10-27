/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.runtime.AuthenticationGate.Caller;
import com.standardapplied.helios.runtime.ServerStubs.RecordingResponse;
import com.standardapplied.helios.session.ResultMessage;
import io.helidon.http.Status;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Proves that a result whose serialization outlasts the caller's identity is not sent: the mapper
 * the long poll is given revokes the identity while it writes the result.
 */
final class ResultLongPollRevocationTest {

  private static final Principal CAROL = new Principal("acme", "carol");

  private final CompletableFuture<Void> revoked = new CompletableFuture<>();
  private final JsonMapper mapper =
      JsonMapper.builder()
          .addModule(
              new SimpleModule("revoke-while-serializing")
                  .addSerializer(ResultMessage.Success.class, new RevokingSerializer()))
          .build();

  @Test
  void revokingTheIdentityWhileTheResultSerializesDisclosesNoResult() {
    var session = new StubSession("sess-carol");
    session.terminate();
    var challenge = new Challenge(TestAuthenticator.CHALLENGE);
    var caller = Caller.start(new Authentication(CAROL, Duration.ofDays(1), revoked), challenge);
    var resp = new RecordingResponse();

    ResultLongPoll.respond(
        session, session.result, ServerStubs.request("timeout=0"), resp.response, mapper, caller);

    assertTrue(revoked.isDone());
    assertEquals(Status.UNAUTHORIZED_401, resp.status());
    assertEquals("{\"error\":\"unauthenticated\"}", resp.body());
    assertEquals(TestAuthenticator.CHALLENGE, resp.header("WWW-Authenticate"));
  }

  private final class RevokingSerializer extends StdSerializer<ResultMessage.Success> {

    RevokingSerializer() {
      super(ResultMessage.Success.class);
    }

    @Override
    public void serialize(
        ResultMessage.Success success, JsonGenerator gen, SerializationContext context) {
      revoked.complete(null);
      gen.writeStartObject();
      gen.writeStringProperty("result", success.result());
      gen.writeEndObject();
    }
  }
}

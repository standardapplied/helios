/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.runtime.RequestLimits;
import com.standardapplied.helios.runtime.RuntimeServer;
import com.standardapplied.helios.runtime.SessionRegistry;
import com.standardapplied.helios.runtime.TrustedProxyAuthenticator;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.SessionPresets;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http1.Http1Config;
import java.net.InetAddress;

/**
 * The README and {@code docs/http.md} blocks that serve sessions over HTTP, one method per block,
 * so the build compiles every one of them. A block's free variables are its method's parameters.
 * {@link QuickStartsDocsTest} holds each block to its method's body; none of these methods runs.
 */
final class HttpSnippets {

  private HttpSnippets() {}

  static void localServer() throws Exception {
    try (var model =
            new AnthropicProvider()
                .create(
                    AnthropicModelId.CLAUDE_SONNET_5_5.id(),
                    ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));
        var server =
            RuntimeServer.builder()
                .withRegistry(SessionRegistry.inMemory())
                .withOptionsFactory(
                    request ->
                        SessionPresets.minimal(model).withSessionId(request.sessionId()).build())
                .withoutAuthentication()
                .withPort(8080)
                .build()) {
      System.in.read();
    }
  }

  static void trustedProxy(Model model) throws Exception {
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                request ->
                    SessionOptions.newBuilder()
                        .withModel(model)
                        .withSessionId(request.sessionId())
                        .withSystemPrompt(promptFor(request.owner().tenant()))
                        .build())
            .withHost("0.0.0.0")
            .withAuthenticator(
                TrustedProxyAuthenticator.newBuilder()
                    .withTrustedProxy(InetAddress.getByName("10.0.0.5"))
                    .build())
            .build();
  }

  static void ownWebServer(RequestLimits limits) {
    var builder =
        WebServer.builder()
            .maxPayloadSize(limits.maxRequestBytes())
            .addProtocol(Http1Config.builder().continueImmediately(true).build());
  }

  private static String promptFor(String tenant) {
    return "You answer for " + tenant + ".";
  }
}

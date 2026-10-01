/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - Helidon SE Runtime Module.
 *
 * <p>Exposes {@link com.standardapplied.helios.session.AgentSession} instances over HTTP and SSE
 * via a Helidon SE {@code WebServer}. The runtime is the deployment shell that fronts the session
 * SDK; the SDK itself never depends on Helidon.
 */
module com.standardapplied.helios.runtime {
  requires com.standardapplied.helios.session;
  requires com.standardapplied.helios.core;
  requires io.helidon.webserver;
  requires io.helidon.webserver.sse;
  requires io.helidon.http;
  requires io.helidon.http.media;
  requires io.helidon.http.sse;
  requires io.helidon.common;
  requires tools.jackson.databind;
  requires java.logging;
  requires java.net.http;

  exports com.standardapplied.helios.runtime;
}

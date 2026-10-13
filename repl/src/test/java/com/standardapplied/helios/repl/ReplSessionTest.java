/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.sandbox.ExecutionRequest;
import com.standardapplied.helios.repl.sandbox.ExecutionResult;
import com.standardapplied.helios.repl.sandbox.Sandbox;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReplSessionTest {

  private static ReplConfig configWith(Sandbox sandbox) {
    return ReplConfig.newBuilder()
        .withSandboxFactory(registry -> sandbox)
        .withExecutionTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Test
  void createReturnsSession() {
    var config = configWith(new FakeSandbox());
    var semaphore = new Semaphore(1);
    var session = ReplSession.create(config, semaphore);

    assertNotNull(session);
    assertTrue(session.isOpen());
    assertEquals(0, semaphore.availablePermits());

    session.close();
    assertEquals(1, semaphore.availablePermits());
  }

  @Test
  void nullConfigThrows() {
    assertThrows(IllegalArgumentException.class, () -> ReplSession.create(null, new Semaphore(1)));
  }

  @Test
  void nullSemaphoreThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ReplSession.create(configWith(new FakeSandbox()), null));
  }

  @Test
  void noPermitsThrows() {
    var semaphore = new Semaphore(0);
    assertThrows(
        ReplException.class, () -> ReplSession.create(configWith(new FakeSandbox()), semaphore));
  }

  @Test
  void factoryExceptionReleasesSemaphore() {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  throw new RuntimeException("sandbox init failed");
                })
            .build();
    var semaphore = new Semaphore(1);

    assertThrows(ReplException.class, () -> ReplSession.create(config, semaphore));
    assertEquals(1, semaphore.availablePermits());
  }

  @Test
  void factoryThrowsReplExceptionDirectly() {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  throw new ReplException("custom repl error");
                })
            .build();
    var semaphore = new Semaphore(1);

    var ex = assertThrows(ReplException.class, () -> ReplSession.create(config, semaphore));
    assertEquals("custom repl error", ex.getMessage());
  }

  @Test
  void executeCode() {
    var sandbox = new FakeSandbox();
    var session = ReplSession.create(configWith(sandbox), new Semaphore(1));

    var result = session.execute("1 + 1");

    assertEquals("ok", result.stdout());

    session.close();
  }

  @Test
  void executeOnClosedSessionThrows() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    session.close();

    assertThrows(ReplException.class, () -> session.execute("code"));
  }

  @Test
  void executeOnDeadSandboxThrows() {
    var sandbox = new FakeSandbox();
    var session = ReplSession.create(configWith(sandbox), new Semaphore(1));
    sandbox.kill();

    assertThrows(ReplException.class, () -> session.execute("code"));

    session.close();
  }

  @Test
  void registryNotNull() {
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));
    assertNotNull(session.registry());
    session.close();
  }

  @Test
  void closeReleasesSemaphore() {
    var semaphore = new Semaphore(1);
    var session = ReplSession.create(configWith(new FakeSandbox()), semaphore);
    assertEquals(0, semaphore.availablePermits());

    session.close();
    assertEquals(1, semaphore.availablePermits());
  }

  @Test
  void doubleCloseIsSafe() {
    var semaphore = new Semaphore(1);
    var session = ReplSession.create(configWith(new FakeSandbox()), semaphore);

    session.close();
    session.close();

    assertEquals(1, semaphore.availablePermits());
    assertFalse(session.isOpen());
  }

  @Test
  void hostFunctionsFromConfigRegistered() {
    var fn = new HostFunction("custom", "custom fn", params -> "ok");
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> new FakeSandbox())
            .withHostFunction(fn)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));

    assertNotNull(session.registry().get("custom"));

    session.close();
  }

  @Test
  void isOpenReflectsState() {
    var sandbox = new FakeSandbox();
    var session = ReplSession.create(configWith(sandbox), new Semaphore(1));
    assertTrue(session.isOpen());

    sandbox.kill();
    assertFalse(session.isOpen());

    session.close();
  }

  @Test
  void calledHostFunctionsCountsAllInvocations() throws Exception {
    var marketQuote = new HostFunction("marketQuote", "fake", params -> "$200");
    var fredIndicator = new HostFunction("fredIndicator", "fake", params -> 1.5);
    var sandbox = new RecordingSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  sandbox.setRegistry(registry);
                  return sandbox;
                })
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunctions(List.of(marketQuote, fredIndicator))
            .build();
    var session = ReplSession.create(config, new Semaphore(1));
    sandbox.behavior =
        registry -> {
          try {
            // 3 marketQuote
            registry.get("marketQuote").handler().handle(Map.of("ticker", "AAPL"));
            registry.get("marketQuote").handler().handle(Map.of("ticker", "GOOG"));
            registry.get("marketQuote").handler().handle(Map.of("ticker", "MSFT"));
            // 1 fredIndicator
            registry.get("fredIndicator").handler().handle(Map.of("seriesId", "GDP"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("noop");

    var counts = session.calledHostFunctions();
    assertEquals(3, counts.get("marketQuote"));
    assertEquals(1, counts.get("fredIndicator"));
    assertEquals(List.of("fredIndicator", "marketQuote"), List.copyOf(counts.keySet()));
    session.close();
  }

  @Test
  void calledHostFunctionsListsEightNamesSortedWhateverOrderTheyWereCalledIn() throws Exception {
    var names = DeclarationOrderFixture.ARGUMENTS;
    var sandbox = new RecordingSandbox();
    var builder = ReplConfig.newBuilder().withExecutionTimeout(Duration.ofSeconds(1));
    names.forEach(name -> builder.withHostFunction(new HostFunction(name, "fake", p -> name)));
    builder.withSandboxFactory(
        registry -> {
          sandbox.setRegistry(registry);
          return sandbox;
        });
    var session = ReplSession.create(builder.build(), new Semaphore(1));
    sandbox.behavior = registry -> names.forEach(name -> handle(registry, name));

    session.execute("noop");

    assertEquals(
        names.stream().sorted().toList(), List.copyOf(session.calledHostFunctions().keySet()));
    session.close();
  }

  private static void handle(HostFunctionRegistry registry, String name) {
    try {
      registry.get(name).handler().handle(Map.of());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void calledHostFunctionsExcludesReservedNames() throws Exception {
    var reserved = new HostFunction("__call", "fake", params -> Map.of("query", "hi"));
    var sandbox = new RecordingSandbox();
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(
                registry -> {
                  sandbox.setRegistry(registry);
                  return sandbox;
                })
            .withExecutionTimeout(Duration.ofSeconds(1))
            .withHostFunction(reserved)
            .build();
    var session = ReplSession.create(config, new Semaphore(1));
    sandbox.behavior =
        registry -> {
          try {
            registry.get("__call").handler().handle(Map.of());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    session.execute("noop");
    assertNull(session.calledHostFunctions().get("__call"));
    session.close();
  }

  /**
   * Sandbox that runs a configurable behavior against the registry on each execute. Lets tests
   * simulate the model emitting code that calls registered host functions, so we can drive
   * predict/marketQuote/etc through the real ReplSession wrapper path.
   */
  private static class RecordingSandbox implements Sandbox {
    java.util.function.Consumer<com.standardapplied.helios.repl.host.HostFunctionRegistry>
        behavior = r -> {};
    private com.standardapplied.helios.repl.host.HostFunctionRegistry registry;

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      // Use a captured registry; in real life RpcChannel does this dispatch.
      // Tests inject the registry via configWith → ReplSession.create wires it.
      if (registry != null) {
        behavior.accept(registry);
      }
      return ExecutionResult.newBuilder().build();
    }

    @Override
    public boolean isAlive() {
      return true;
    }

    @Override
    public void close() {}

    void setRegistry(com.standardapplied.helios.repl.host.HostFunctionRegistry registry) {
      this.registry = registry;
    }
  }

  @Test
  void executeStampsWallClockDurationOntoResult() {
    // The sandbox returns Duration.ZERO via the legacy convenience constructor. ReplSession.execute
    // must overwrite it with the measured wall clock so the model-facing budget header can show
    // last_exec=... per the Prime Intellect blog's "tell the model how long the call took" lever.
    var session = ReplSession.create(configWith(new FakeSandbox()), new Semaphore(1));

    var result = session.execute("x");

    assertNotNull(result.duration(), "duration must always be non-null");
    assertFalse(
        result.duration().isZero(),
        "System.nanoTime delta around the sandbox call should be > 0 ns");
    session.close();
  }

  private static class FakeSandbox implements Sandbox {
    private final AtomicBoolean alive = new AtomicBoolean(true);

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      return ExecutionResult.success("ok");
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    void kill() {
      alive.set(false);
    }

    @Override
    public void close() {
      alive.set(false);
    }
  }
}

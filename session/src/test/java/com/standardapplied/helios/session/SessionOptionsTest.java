/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.context.TokenCounter;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.execution.NoopExecutionProvider;
import ai.singlr.session.hooks.Hook;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.hooks.PreToolUseHook;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SessionOptionsTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
      }

      @Override
      public String id() {
        return "stub";
      }

      @Override
      public String provider() {
        return "stub";
      }
    };
  }

  // ── canonical constructor validation ──────────────────────────────────────

  @Test
  void canonicalConstructorRejectsNullModel() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    null,
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullSessionId() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    null,
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsBlankSessionId() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "   ",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullLimits() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    null,
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullConcurrency() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    null,
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("concurrency must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullClock() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    null,
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullTools() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    null,
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("tools must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullHooks() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    null,
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullPermission() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    null,
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("permission must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullMemoryBackend() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    null,
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("memoryBackend must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCostCalculator() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    null,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("costCalculator must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullExecutionProvider() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    null,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("executionProvider must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullOutputSchema() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    null,
                    Optional.empty(),
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("outputSchema must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullSystemPrompt() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    null,
                    TokenCounter.charBased(),
                    ContextCompactor.disabled()));
    assertEquals("systemPrompt must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullTokenCounter() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    null,
                    ContextCompactor.disabled()));
    assertEquals("tokenCounter must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullContextCompactor() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new SessionOptions(
                    stubModel(),
                    "sess",
                    SessionLimits.defaults(),
                    ConcurrencyLimits.defaults(),
                    Clock.systemUTC(),
                    ToolRegistry.empty(),
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    CostCalculator.ZERO,
                    NoopExecutionProvider.INSTANCE,
                    Optional.empty(),
                    Optional.empty(),
                    TokenCounter.charBased(),
                    null));
    assertEquals("contextCompactor must not be null", ex.getMessage());
  }

  @Test
  void builderDefaultsContextCompactorToDropMiddle() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertNotNull(opts.contextCompactor());
    // default is DropMiddleToolResultsCompactor — verify it's not the disabled singleton
    assertNotSame(ContextCompactor.disabled(), opts.contextCompactor());
  }

  @Test
  void withContextCompactorRejectsNull() {
    var b = SessionOptions.newBuilder().withModel(stubModel());
    var ex = assertThrows(NullPointerException.class, () -> b.withContextCompactor(null));
    assertEquals("contextCompactor must not be null", ex.getMessage());
  }

  @Test
  void withContextCompactorIsThreadedThroughBuildAndToBuilder() {
    var disabled = ContextCompactor.disabled();
    var opts =
        SessionOptions.newBuilder().withModel(stubModel()).withContextCompactor(disabled).build();
    assertSame(disabled, opts.contextCompactor());
    assertSame(disabled, opts.toBuilder().build().contextCompactor());
  }

  @Test
  void builderDefaultsTokenCounterToCharBased() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertSame(TokenCounter.charBased(), opts.tokenCounter());
  }

  @Test
  void withTokenCounterRejectsNull() {
    var b = SessionOptions.newBuilder().withModel(stubModel());
    var ex = assertThrows(NullPointerException.class, () -> b.withTokenCounter(null));
    assertEquals("tokenCounter must not be null", ex.getMessage());
  }

  @Test
  void withTokenCounterIsThreadedThroughBuildAndToBuilder() {
    TokenCounter custom = msgs -> 42L;
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withTokenCounter(custom).build();
    assertSame(custom, opts.tokenCounter());
    assertSame(custom, opts.toBuilder().build().tokenCounter());
  }

  @Test
  void builderDefaultsSystemPromptToEmptyOptional() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertTrue(opts.systemPrompt().isEmpty());
  }

  @Test
  void withSystemPromptRetainsValue() {
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSystemPrompt("you are helpful")
            .build();
    assertEquals("you are helpful", opts.systemPrompt().orElseThrow());
  }

  @Test
  void withSystemPromptTreatsBlankAsClear() {
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSystemPrompt("seed")
            .withSystemPrompt("   ")
            .build();
    assertTrue(opts.systemPrompt().isEmpty());
  }

  @Test
  void withSystemPromptAcceptsNullAsClear() {
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSystemPrompt("seed")
            .withSystemPrompt(null)
            .build();
    assertTrue(opts.systemPrompt().isEmpty());
  }

  @Test
  void withSystemPromptIsThreadedThroughToBuilder() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withSystemPrompt("ctx").build();
    assertEquals("ctx", opts.toBuilder().build().systemPrompt().orElseThrow());
  }

  @Test
  void builderDefaultsOutputSchemaToEmptyOptional() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertTrue(opts.outputSchema().isEmpty());
  }

  @Test
  void withOutputSchemaRetainsValue() {
    var schema = OutputSchema.of(SampleOutput.class);
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withOutputSchema(schema).build();
    assertSame(schema, opts.outputSchema().orElseThrow());
  }

  @Test
  void withOutputSchemaAcceptsNullAsClear() {
    var schema = OutputSchema.of(SampleOutput.class);
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withOutputSchema(schema)
            .withOutputSchema(null)
            .build();
    assertTrue(opts.outputSchema().isEmpty());
  }

  @Test
  void withOutputSchemaIsThreadedThroughToBuilder() {
    var schema = OutputSchema.of(SampleOutput.class);
    var original =
        SessionOptions.newBuilder().withModel(stubModel()).withOutputSchema(schema).build();
    var copy = original.toBuilder().build();
    assertSame(schema, copy.outputSchema().orElseThrow());
  }

  /** Trivial record used only for {@link OutputSchema} test fixtures. */
  record SampleOutput(String value) {}

  @Test
  void builderDefaultsExecutionProviderToNoop() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertSame(NoopExecutionProvider.INSTANCE, opts.executionProvider());
  }

  @Test
  void withExecutionProviderRejectsNull() {
    var b = SessionOptions.newBuilder().withModel(stubModel());
    var ex = assertThrows(NullPointerException.class, () -> b.withExecutionProvider(null));
    assertEquals("executionProvider must not be null", ex.getMessage());
  }

  @Test
  void withExecutionProviderIsThreadedThroughBuildAndToBuilder() {
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withExecutionProvider(NoopExecutionProvider.INSTANCE)
            .build();
    assertSame(NoopExecutionProvider.INSTANCE, opts.executionProvider());
    assertSame(NoopExecutionProvider.INSTANCE, opts.toBuilder().build().executionProvider());
  }

  @Test
  void builderDefaultsCostCalculatorToZero() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertSame(CostCalculator.ZERO, opts.costCalculator());
  }

  @Test
  void withCostCalculatorRejectsNull() {
    var b = SessionOptions.newBuilder().withModel(stubModel());
    var ex = assertThrows(NullPointerException.class, () -> b.withCostCalculator(null));
    assertEquals("costCalculator must not be null", ex.getMessage());
  }

  @Test
  void withCostCalculatorIsThreadedThroughBuildAndToBuilder() {
    CostCalculator custom = (id, u) -> CostEstimate.ofUsd(0.42);
    var opts =
        SessionOptions.newBuilder().withModel(stubModel()).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
    assertSame(custom, opts.toBuilder().build().costCalculator());
  }

  @Test
  void builderDefaultsPermissionToEmptyOptional() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertTrue(opts.permission().isEmpty());
  }

  @Test
  void withPermissionAcceptsNullAsClear() {
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withPermission(Permission.defaultInWorkspace())
            .withPermission(null)
            .build();
    assertTrue(opts.permission().isEmpty());
  }

  @Test
  void withPermissionRetainsValue() {
    var perm = Permission.defaultInWorkspace();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withPermission(perm).build();
    assertSame(perm, opts.permission().orElseThrow());
  }

  // ── builder happy path ────────────────────────────────────────────────────

  @Test
  void builderProducesValidOptionsWithModelAlone() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertNotNull(opts.model());
    assertTrue(opts.sessionId().startsWith("sess-"));
    assertSame(SessionLimits.defaults(), opts.limits());
    assertSame(ConcurrencyLimits.defaults(), opts.concurrency());
    assertNotNull(opts.clock());
  }

  @Test
  void builderRespectsAllOverrides() {
    var fixed = Clock.fixed(Instant.parse("2026-05-14T19:00:00Z"), ZoneOffset.UTC);
    var customLimits =
        SessionLimits.newBuilder()
            .withMaxTurns(50)
            .withMaxWallClock(Duration.ofMinutes(30))
            .withToolTimeoutDefault(Duration.ofSeconds(20))
            .withMaxContextTokens(64_000L)
            .build();
    var customConcurrency = new ConcurrencyLimits(8, 2, 1, 32);
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSessionId("explicit-id")
            .withLimits(customLimits)
            .withConcurrencyLimits(customConcurrency)
            .withClock(fixed)
            .build();
    assertEquals("explicit-id", opts.sessionId());
    assertSame(customLimits, opts.limits());
    assertSame(customConcurrency, opts.concurrency());
    assertSame(fixed, opts.clock());
  }

  @Test
  void builderAutoGeneratesUniqueSessionIds() {
    var a = SessionOptions.newBuilder().withModel(stubModel()).build();
    var b = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertNotEquals(a.sessionId(), b.sessionId());
  }

  @Test
  void buildWithoutModelThrows() {
    var ex = assertThrows(IllegalStateException.class, () -> SessionOptions.newBuilder().build());
    assertTrue(ex.getMessage().startsWith("model is required"));
  }

  // ── builder validation per setter ────────────────────────────────────────

  @Test
  void withModelRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withModel(null));
    assertEquals("model must not be null", ex.getMessage());
  }

  @Test
  void withSessionIdRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> SessionOptions.newBuilder().withSessionId(null));
    assertEquals("sessionId must not be null", ex.getMessage());
  }

  @Test
  void withSessionIdRejectsBlank() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> SessionOptions.newBuilder().withSessionId("  "));
    assertEquals("sessionId must not be blank", ex.getMessage());
  }

  @Test
  void withLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> SessionOptions.newBuilder().withLimits(null));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @Test
  void withConcurrencyLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> SessionOptions.newBuilder().withConcurrencyLimits(null));
    assertEquals("concurrency must not be null", ex.getMessage());
  }

  @Test
  void withClockRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withClock(null));
    assertEquals("clock must not be null", ex.getMessage());
  }

  @Test
  void withToolsRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withTools(null));
    assertEquals("tools must not be null", ex.getMessage());
  }

  @Test
  void builderDefaultsToolsToEmpty() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertEquals(0, opts.tools().size());
  }

  @Test
  void builderDefaultsHooksToEmpty() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertEquals(0, opts.hooks().size());
  }

  @Test
  void withHookAppendsAHookToTheList() {
    PreToolUseHook hook = (call, ctx) -> HookOutcome.cont();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withHook(hook).build();
    assertEquals(1, opts.hooks().size());
    assertSame(hook, opts.hooks().get(0));
  }

  @Test
  void withHooksReplacesTheList() {
    PreToolUseHook a = (call, ctx) -> HookOutcome.cont();
    PreToolUseHook b = (call, ctx) -> HookOutcome.cont();
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withHook(a)
            .withHooks(List.of(b))
            .build();
    assertEquals(1, opts.hooks().size());
    assertSame(b, opts.hooks().get(0));
  }

  @Test
  void withHookRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withHook(null));
    assertEquals("hook must not be null", ex.getMessage());
  }

  @Test
  void withHooksRejectsNullList() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withHooks(null));
    assertEquals("hooks must not be null", ex.getMessage());
  }

  @Test
  void withHooksRejectsListContainingNull() {
    var list = new ArrayList<Hook>();
    list.add(null);
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withHooks(list));
    assertEquals("hooks must not contain null", ex.getMessage());
  }

  @Test
  void builderHonorsCustomTools() {
    var custom = ToolRegistry.empty();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withTools(custom).build();
    assertSame(custom, opts.tools());
  }

  // ── toBuilder round-trip ──────────────────────────────────────────────────

  @Test
  void toBuilderRoundTripsAllFields() {
    var original = SessionOptions.newBuilder().withModel(stubModel()).withSessionId("orig").build();
    var copy = original.toBuilder().build();
    assertEquals(original, copy);
  }

  @Test
  void toBuilderAllowsFieldOverride() {
    var original = SessionOptions.newBuilder().withModel(stubModel()).withSessionId("orig").build();
    var variant = original.toBuilder().withSessionId("variant").build();
    assertEquals("variant", variant.sessionId());
    assertSame(original.model(), variant.model());
  }
}

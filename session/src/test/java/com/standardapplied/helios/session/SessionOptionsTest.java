/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.BlockingModel;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.core.trace.TraceDetail;
import com.standardapplied.helios.core.trace.TraceListener;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SessionOptionsTest {

  private static Model stubModel() {
    return new BlockingModel() {
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

  // ── secret registry ───────────────────────────────────────────────────────

  @Test
  void secretRegistryDefaultsToANewEmptyRegistryPerBuild() {
    var builder = SessionOptions.newBuilder().withModel(stubModel());

    var first = builder.build().secretRegistry();

    assertEquals(0, first.size());
    assertNotSame(first, builder.build().secretRegistry());
  }

  @Test
  void secretRegistryIsTheOneSetAndSurvivesToBuilder() {
    var registry = new SecretRegistry();

    var options = SessionOptions.newBuilder().withModel(stubModel()).withSecretRegistry(registry);

    assertSame(registry, options.build().secretRegistry());
    assertSame(registry, options.build().toBuilder().build().secretRegistry());
  }

  @Test
  void withSecretRegistryRejectsNull() {
    var builder = SessionOptions.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> builder.withSecretRegistry(null));
    assertEquals("secretRegistry must not be null", ex.getMessage());
  }

  // ── canonical constructor validation ──────────────────────────────────────

  @ParameterizedTest
  @ValueSource(
      strings = {
        "model",
        "sessionId",
        "limits",
        "clock",
        "tools",
        "hooks",
        "permission",
        "memoryBackend",
        "costCalculator",
        "executionProvider",
        "outputSchema",
        "systemPrompt",
        "tokenCounter",
        "contextCompactor",
        "secretRegistry",
        "store",
        "traceListener",
        "traceDetail"
      })
  void canonicalConstructorRejectsANullComponent(String component) {
    var ex = assertThrows(NullPointerException.class, () -> canonical(component, null));
    assertEquals(component + " must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsAnIdOutsideTheRule() {
    var ex = assertThrows(IllegalArgumentException.class, () -> canonical("sessionId", "../x"));
    assertEquals(
        "sessionId must be 1 to 128 characters of [A-Za-z0-9._:-] not starting with '.'",
        ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void canonicalConstructorRejectsANonPositiveQueueCapacity(int capacity) {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> canonical("maxQueuedUserMessages", capacity));
    assertEquals("maxQueuedUserMessages must be positive, got " + capacity, ex.getMessage());
  }

  /**
   * The canonical constructor called with the components of a valid options, {@code component}
   * replaced by {@code value}; what it throws is rethrown as itself.
   */
  private static SessionOptions canonical(String component, Object value) throws Exception {
    var valid = SessionOptions.newBuilder().withModel(stubModel()).withSessionId("sess").build();
    var components = SessionOptions.class.getRecordComponents();
    var types = new Class<?>[components.length];
    var arguments = new Object[components.length];
    for (var i = 0; i < components.length; i++) {
      types[i] = components[i].getType();
      arguments[i] =
          components[i].getName().equals(component)
              ? value
              : components[i].getAccessor().invoke(valid);
    }
    try {
      return SessionOptions.class.getConstructor(types).newInstance(arguments);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  @Test
  void builderDefaultsContextCompactorToSummarizing() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertInstanceOf(SummarizingCompactor.class, opts.contextCompactor());
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
  void builderDefaultsToNoExecutionProviderAndNoAskUserQuestion() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertEquals(Optional.empty(), opts.executionProvider());
    assertFalse(opts.askUserQuestion());
  }

  @Test
  void withAskUserQuestionIsThreadedThroughBuildAndToBuilder() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withAskUserQuestion().build();
    assertTrue(opts.askUserQuestion());
    assertTrue(opts.toBuilder().build().askUserQuestion());
  }

  @Test
  void withExecutionProviderRejectsNull() {
    var b = SessionOptions.newBuilder().withModel(stubModel());
    var ex = assertThrows(NullPointerException.class, () -> b.withExecutionProvider(null));
    assertEquals("executionProvider must not be null", ex.getMessage());
  }

  @Test
  void withExecutionProviderIsThreadedThroughBuildAndToBuilder() {
    var provider = new RecordingProvider(new ArrayList<>());
    var opts =
        SessionOptions.newBuilder().withModel(stubModel()).withExecutionProvider(provider).build();
    assertSame(provider, opts.executionProvider().orElseThrow());
    assertSame(provider, opts.toBuilder().build().executionProvider().orElseThrow());
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
  void builderDefaultsPermissionToTheDefaultMode() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertEquals(Permission.empty(PermissionMode.DEFAULT), opts.permission());
  }

  @Test
  void withPermissionRejectsNull() {
    var builder = SessionOptions.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> builder.withPermission(null));
    assertEquals("permission must not be null", ex.getMessage());
  }

  @Test
  void withPermissionIsThreadedThroughBuildAndToBuilder() {
    var perm = Permission.defaultInWorkspace();
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withPermission(perm).build();
    assertSame(perm, opts.permission());
    assertSame(perm, opts.toBuilder().build().permission());
  }

  // ── builder happy path ────────────────────────────────────────────────────

  @Test
  void builderProducesValidOptionsWithModelAlone() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertNotNull(opts.model());
    assertTrue(opts.sessionId().startsWith("sess-"));
    assertSame(SessionLimits.defaults(), opts.limits());
    assertEquals(256, opts.maxQueuedUserMessages());
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
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSessionId("explicit-id")
            .withLimits(customLimits)
            .withMaxQueuedUserMessages(32)
            .withClock(fixed)
            .build();
    assertEquals("explicit-id", opts.sessionId());
    assertSame(customLimits, opts.limits());
    assertEquals(32, opts.maxQueuedUserMessages());
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
  void withSessionIdRejectsAnIdOutsideTheRule() {
    var builder = SessionOptions.newBuilder();
    for (var id : List.of("", "  ", "../x", "a/b", ".hidden", "a".repeat(129), "a b")) {
      var ex = assertThrows(IllegalArgumentException.class, () -> builder.withSessionId(id), id);
      assertEquals(
          "sessionId must be 1 to 128 characters of [A-Za-z0-9._:-] not starting with '.'",
          ex.getMessage());
    }
  }

  @Test
  void withSessionIdAcceptsAnIdInsideTheRule() {
    var generated = SessionOptions.newBuilder().withModel(stubModel()).build().sessionId();
    for (var id : List.of(generated, "member:42", "a.b_c-1", "a".repeat(128))) {
      assertEquals(
          id,
          SessionOptions.newBuilder().withModel(stubModel()).withSessionId(id).build().sessionId());
    }
  }

  @Test
  void withLimitsRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> SessionOptions.newBuilder().withLimits(null));
    assertEquals("limits must not be null", ex.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void withMaxQueuedUserMessagesRejectsANonPositiveCapacity(int capacity) {
    var builder = SessionOptions.newBuilder();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> builder.withMaxQueuedUserMessages(capacity));
    assertEquals("maxQueuedUserMessages must be positive, got " + capacity, ex.getMessage());
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
  void withToolAppendsAfterThePresetTools() {
    var first = binding("First");
    var second = binding("Second");
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withTools(List.of(first))
            .withTool(second)
            .build();
    assertEquals(List.of(first, second), opts.tools());
  }

  @Test
  void withToolsReplacesEveryToolAddedBefore() {
    var kept = binding("Kept");
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withTool(binding("Dropped"))
            .withTools(List.of(kept))
            .build();
    assertEquals(List.of(kept), opts.tools());
  }

  @Test
  void withToolRejectsNull() {
    var ex =
        assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withTool(null));
    assertEquals("tool must not be null", ex.getMessage());
  }

  @Test
  void withToolsRejectsAListContainingNull() {
    var list = new ArrayList<ToolBinding>();
    list.add(null);
    assertThrows(NullPointerException.class, () -> SessionOptions.newBuilder().withTools(list));
  }

  @Test
  void theOptionsCopyTheirTools() {
    var tools = new ArrayList<>(List.of(binding("A")));
    var opts = SessionOptions.newBuilder().withModel(stubModel()).withTools(tools).build();
    tools.add(binding("B"));
    assertEquals(List.of("A"), opts.tools().stream().map(ToolBinding::name).toList());
  }

  private static ToolBinding binding(String name) {
    return ToolBinding.newBuilder(
            Tool.newBuilder()
                .withName(name)
                .withDescription("test tool")
                .withExecutor((args, ctx) -> ToolResult.success("ok"))
                .build())
        .withCategory(ToolCategory.READ)
        .build();
  }

  // ── toBuilder round-trip ──────────────────────────────────────────────────

  @Test
  void toBuilderRoundTripsAllFields() {
    var original =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withSessionId("orig")
            .withMaxQueuedUserMessages(2)
            .withTool(binding("Tool"))
            .withExecutionProvider(new RecordingProvider(new ArrayList<>()))
            .withAskUserQuestion()
            .build();
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

  // ── tracing ───────────────────────────────────────────────────────────────

  @Test
  void tracingDefaultsToNoListenerAndStandardDetail() {
    var opts = SessionOptions.newBuilder().withModel(stubModel()).build();
    assertTrue(opts.traceListener().isEmpty());
    assertEquals(TraceDetail.STANDARD, opts.traceDetail());
  }

  @Test
  void tracingIsThreadedThroughBuildAndToBuilder() {
    TraceListener listener = trace -> {};
    var opts =
        SessionOptions.newBuilder()
            .withModel(stubModel())
            .withTraceListener(listener)
            .withTraceDetail(TraceDetail.VERBOSE)
            .build();
    var copy = opts.toBuilder().build();
    assertSame(listener, copy.traceListener().orElseThrow());
    assertEquals(TraceDetail.VERBOSE, copy.traceDetail());
  }

  @Test
  void withTraceListenerAndWithTraceDetailRejectNull() {
    var builder = SessionOptions.newBuilder();
    assertEquals(
        "traceListener must not be null",
        assertThrows(NullPointerException.class, () -> builder.withTraceListener(null))
            .getMessage());
    assertEquals(
        "traceDetail must not be null",
        assertThrows(NullPointerException.class, () -> builder.withTraceDetail(null)).getMessage());
  }
}

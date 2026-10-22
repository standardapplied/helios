/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.test.HookInputs.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.hooks.OnStreamEventHook;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * No registered secret reaches a log handler through a hook's or a tool's failure, or an event
 * subscriber through a tool call's arguments; the tool and the history still see the arguments as
 * the model and the hooks wrote them.
 */
final class HookAndEventRedactionTest {

  private static final String SECRET = "sk-test-0123456789abcdef";
  private static final String MARKER = "<redacted:API_KEY>";

  /** The default mapper, as the runtime writes an event to its SSE stream. */
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final WriteToolSessions sessions = new WriteToolSessions();

  /** A {@code PostToolUse} hook that throws {@code failure}. */
  private record Auditor(RuntimeException failure) implements PostToolUseHook {

    @Override
    public HookOutcome afterTool(ToolCall call, ToolResult result, HookContext ctx) {
      throw failure;
    }
  }

  /** A stream-event hook that throws {@code failure} on the {@code ToolUse} event. */
  private record Observer(RuntimeException failure) implements OnStreamEventHook {

    @Override
    public void onEvent(QueryEvent event, HookContext ctx) {
      if (event instanceof QueryEvent.ToolUse) {
        throw failure;
      }
    }
  }

  private static SecretRegistry secrets() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", SECRET);
    return secrets;
  }

  private SessionOptions.Builder options() {
    return SessionOptions.newBuilder()
        .withSecretRegistry(secrets())
        .withTools(new ToolRegistry(List.of(sessions.writeTool())));
  }

  /** The one {@code WARNING} the hook registry logged while {@code options} ran a write. */
  private static LogRecord onlyHookWarning(SessionOptions.Builder options) {
    var logged = new ArrayList<LogRecord>();
    try (var ignored = LogCapture.of(HookRegistry.class, logged)) {
      WriteToolSessions.run(options, WriteToolSessions.write("/tmp/a"));
    }
    assertEquals(1, logged.size(), "log records");
    var only = logged.getFirst();
    assertEquals(Level.WARNING, only.getLevel());
    assertFalse(RedactedLogs.text(only).contains(SECRET), RedactedLogs.text(only));
    return only;
  }

  // ── logs ──────────────────────────────────────────────────────────────────

  @Test
  void anObserverHookThatThrowsIsLoggedWithARedactedCopy() {
    var failure = new IllegalStateException("saw " + SECRET);

    var logged = onlyHookWarning(options().withHook(new Auditor(failure)));

    assertEquals("hook Auditor threw; treating as Continue", logged.getMessage());
    RedactedLogs.assertCopyOf(
        failure, logged.getThrown(), List.of("java.lang.IllegalStateException: saw " + MARKER));
  }

  @Test
  void aStreamEventHookThatThrowsIsLoggedWithARedactedCopy() {
    var failure = new IllegalStateException("saw " + SECRET);

    var logged = onlyHookWarning(options().withHook(new Observer(failure)));

    assertEquals("hook Observer threw on stream event", logged.getMessage());
    RedactedLogs.assertCopyOf(
        failure, logged.getThrown(), List.of("java.lang.IllegalStateException: saw " + MARKER));
  }

  @Test
  void aCauseChainIsRedactedAtEveryLevel() {
    var failure =
        new IllegalStateException("outer", new IllegalArgumentException("inner " + SECRET));

    var logged = onlyHookWarning(options().withHook(new Auditor(failure)));

    RedactedLogs.assertCopyOf(
        failure,
        logged.getThrown(),
        List.of(
            "java.lang.IllegalStateException: outer",
            "java.lang.IllegalArgumentException: inner " + MARKER));
  }

  // ── events ────────────────────────────────────────────────────────────────

  private static Map<String, Object> arguments(String path, String secret) {
    var body = new LinkedHashMap<String, Object>();
    body.put("meta", Map.of("note", "token " + secret, "key-" + secret, 1));
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("path", path);
    arguments.put("body", body);
    return arguments;
  }

  private static ToolCall secretCall(String path) {
    return new ToolCall("c1", "Write", arguments(path, SECRET));
  }

  /** A guard that moves the call to {@code /tmp/b}, keeping its other arguments. */
  private static PreToolUseHook relocating() {
    return named(
        "Relocate",
        (call, ctx) -> {
          var moved = new LinkedHashMap<>(call.arguments());
          moved.put("path", "/tmp/b");
          return HookOutcome.mutateArgs(moved);
        });
  }

  private static List<String> toolEventsAsJson(WriteToolSessions.Run run) {
    return run.events().events().stream()
        .filter(
            event ->
                event instanceof QueryEvent.ToolUse
                    || event instanceof QueryEvent.ToolMutated
                    || event instanceof QueryEvent.ToolBlocked
                    || event instanceof QueryEvent.ToolResult)
        .map(JSON::writeValueAsString)
        .toList();
  }

  @Test
  void toolCallEventsCarryRedactedArgumentsWhileTheToolTheHooksAndTheHistorySeeThemWhole() {
    var afterTool = new CopyOnWriteArrayList<Map<String, Object>>();
    PostToolUseHook recording =
        (call, result, ctx) -> {
          afterTool.add(call.arguments());
          return HookOutcome.cont();
        };
    var options = options().withHook(relocating()).withHook(recording);

    var run = WriteToolSessions.run(options, secretCall("/tmp/a"));

    var json = toolEventsAsJson(run);
    assertEquals(3, json.size(), "ToolMutated, ToolUse and ToolResult: " + json);
    json.forEach(event -> assertFalse(event.contains(SECRET), event));
    var mutated = run.events().eventsOf(QueryEvent.ToolMutated.class).getFirst();
    assertEquals(
        List.of(arguments("/tmp/a", MARKER), arguments("/tmp/b", MARKER)),
        List.of(mutated.inputBefore(), mutated.inputAfter()));
    assertEquals(
        new ToolCall("c1", "Write", arguments("/tmp/b", MARKER)),
        run.events().eventsOf(QueryEvent.ToolUse.class).getFirst().call());
    assertEquals(List.of(arguments("/tmp/b", SECRET)), sessions.executed);
    assertEquals(sessions.executed, afterTool);
    var history =
        run.model().calls().get(1).stream()
            .filter(message -> message.toolCalls() != null && !message.toolCalls().isEmpty())
            .map(Message::toolCalls)
            .toList();
    assertEquals(List.of(List.of(secretCall("/tmp/a"))), history);
  }

  @Test
  void aDeniedCallsBlockedEventCarriesRedactedArguments() {
    var deny = PermissionRule.withGlob(PermissionEffect.DENY, "Write", "/etc/**");
    var permission = new Permission(PermissionMode.DEFAULT, List.of(), List.of(), List.of(deny));
    var options = sessions.options(permission).withSecretRegistry(secrets());

    var run = WriteToolSessions.run(options, secretCall("/etc/passwd"));

    assertEquals(
        new ToolCall("c1", "Write", arguments("/etc/passwd", MARKER)), run.onlyBlock().call());
    toolEventsAsJson(run).forEach(event -> assertFalse(event.contains(SECRET), event));
    assertEquals(List.of(), sessions.executed);
  }

  // ── event copies of valid calls ───────────────────────────────────────────

  /** A key at the limit that ends in {@code secret}, so redacting it overflows the limit. */
  private static String keyEndingIn(String secret) {
    return "k".repeat(JsonSnapshot.MAX_KEY_LENGTH - secret.length()) + secret;
  }

  private static SecretRegistry shortSecret() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", "12345678");
    return secrets;
  }

  @Test
  void aCallWhoseRedactedKeyOverflowsTheLimitStillRuns() {
    var arguments = Map.<String, Object>of("path", "/tmp/a", keyEndingIn("12345678"), 1);
    var options = options().withSecretRegistry(shortSecret());

    var run = WriteToolSessions.run(options, new ToolCall("c1", "Write", arguments));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals(List.of(arguments), sessions.executed);
    var shown = run.events().eventsOf(QueryEvent.ToolUse.class).getFirst().call().arguments();
    assertEquals(
        ("k".repeat(JsonSnapshot.MAX_KEY_LENGTH - 8) + MARKER)
                .substring(0, JsonSnapshot.MAX_KEY_LENGTH - 1)
            + "…",
        shown.keySet().stream().filter(key -> !"path".equals(key)).findFirst().orElseThrow());
  }

  @Test
  void aSecretTheToolRegistersDoesNotCostTheCallItsResultEvent() {
    var secrets = new SecretRegistry();
    var tool =
        Tool.newBuilder()
            .withName("Register")
            .withDescription("register")
            .withExecutor(
                (args, ctx) -> {
                  secrets.register("API_KEY", "12345678");
                  return ToolResult.success("registered");
                })
            .build();
    var options =
        SessionOptions.newBuilder()
            .withSecretRegistry(secrets)
            .withTools(
                new ToolRegistry(
                    List.of(ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build())));
    var call = new ToolCall("c1", "Register", Map.of(keyEndingIn("12345678"), 1));

    var run = WriteToolSessions.run(options, call);

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals("registered", run.onlyResult().output());
  }

  @Test
  void anUnpairedSurrogateKeyIsShownAsTheToolReceivedIt() {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("path", "/tmp/a");
    arguments.put("\uD800", 1);
    arguments.put("?", 2);

    var run =
        WriteToolSessions.run(
            options().withSecretRegistry(new SecretRegistry()),
            new ToolCall("c1", "Write", arguments));

    assertEquals(List.of(arguments), sessions.executed);
    assertEquals(
        arguments, run.events().eventsOf(QueryEvent.ToolUse.class).getFirst().call().arguments());
  }
}

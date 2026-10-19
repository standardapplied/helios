/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.PathToolSessions.assertNoSecretEmitted;
import static com.standardapplied.helios.session.PathToolSessions.call;
import static com.standardapplied.helios.session.PathToolSessions.output;
import static com.standardapplied.helios.session.PathToolSessions.policy;
import static com.standardapplied.helios.session.PathToolSessions.read;
import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A path tool's permission key is the canonical path it touches: a deny rule binds every spelling
 * of its target, a path that names no target is refused before any rule or approver sees it, a
 * target swapped after approval is refused when the tool runs, and a rule no canonical path can
 * match fails the session's creation.
 */
final class CanonicalPermissionPathsTest {

  private static final PermissionRule DENY_READ_SECRETS =
      PermissionRule.withGlob(PermissionEffect.DENY, ReadTool.NAME, "./secrets/**");

  @TempDir Path tmp;

  private PathToolSessions sessions;
  private Path outside;

  @BeforeEach
  void fixture() throws IOException {
    sessions = new PathToolSessions(Files.createDirectory(tmp.resolve("ws")));
    outside = Files.createDirectory(tmp.resolve("outside")).toRealPath();
    Files.createSymbolicLink(sessions.root.resolve("dangling"), sessions.root.resolve("gone"));
    Files.createSymbolicLink(sessions.root.resolve("loop"), sessions.root.resolve("loop"));
    Files.createSymbolicLink(sessions.root.resolve("out"), outside);
  }

  private static Function<AgentSession, Consumer<QueryEvent>> denyingQuestions() {
    return session -> QuestionAnswers.selecting(session, "Deny");
  }

  // ── every spelling of a denied target is denied ──────────────────────────

  @ParameterizedTest(name = "Read {0}")
  @ValueSource(
      strings = {
        "./secrets/key",
        "secrets/key",
        "docs/../secrets/key",
        "./secrets/./key",
        "<root>/secrets/key",
        "link/key"
      })
  void everySpellingOfADeniedFileIsRefusedUnread(String spelling) {
    var path = spelling.replace("<root>", sessions.root.toString());

    var run = run(sessions.options(policy(PermissionMode.DEFAULT, DENY_READ_SECRETS)), read(path));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule Read(./secrets/**) matched Read(./secrets/key)"),
        run.onlyResult().outcome());
    assertNoSecretEmitted(run);
    assertEquals(0, sessions.readRuns.get());
  }

  @ParameterizedTest(name = "DENY Read({0}) refuses Read {1}")
  @CsvSource({"./**/**/secrets/**, secrets/key", "./🔒/**, 🔒/key"})
  void aDenyRuleBindsAdjacentRecursiveWildcardsAndSupplementaryCharacters(String glob, String path)
      throws IOException {
    sessions.write("🔒/key", PathToolSessions.SECRET);
    var deny = PermissionRule.withGlob(PermissionEffect.DENY, ReadTool.NAME, glob);

    var run = run(sessions.options(policy(PermissionMode.DEFAULT, deny)), read(path));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule Read(" + glob + ") matched Read(./" + path + ")"),
        run.onlyResult().outcome());
    assertNoSecretEmitted(run);
    assertEquals(0, sessions.readRuns.get());
  }

  static Stream<Arguments> deniedDirectorySpellings() {
    return Stream.of("LS", "Grep")
        .flatMap(tool -> Stream.of("secrets", "./secrets").map(path -> Arguments.of(tool, path)));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("deniedDirectorySpellings")
  void aDeniedDirectoryIsRefusedToTheToolsThatListOrSearchIt(String tool, String path) {
    var permission =
        policy(
            PermissionMode.DEFAULT,
            PermissionRule.withGlob(PermissionEffect.DENY, "LS", "./secrets/**"),
            PermissionRule.withGlob(PermissionEffect.DENY, "Grep", "./secrets/**"));

    var run = run(sessions.options(permission), call(tool, Map.of("pattern", "TOP", "path", path)));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule " + tool + "(./secrets/**) matched " + tool + "(./secrets)"),
        run.onlyResult().outcome());
    assertNoSecretEmitted(run);
  }

  @ParameterizedTest(name = "MemoryRead {0}")
  @ValueSource(strings = {"/memories/./locked/a.md", "/memories/x/../locked/a.md"})
  void everySpellingOfADeniedMemoryEntryIsRefused(String path) throws IOException {
    sessions.write(".agent/memory/locked/a.md", PathToolSessions.SECRET);
    var permission =
        policy(
            PermissionMode.DEFAULT,
            PermissionRule.withGlob(PermissionEffect.DENY, "MemoryRead", "/memories/locked/**"));

    var run = run(sessions.options(permission), call("MemoryRead", Map.of("path", path)));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "deny rule MemoryRead(/memories/locked/**) matched MemoryRead(/memories/locked/a.md)"),
        run.onlyResult().outcome());
    assertNoSecretEmitted(run);
  }

  // ── a path that names no target is refused before authorization ──────────

  private Object spelled(Object path) {
    return path instanceof String s ? s.replace("<outside>", outside.toString()) : path;
  }

  static Stream<Arguments> unresolvablePaths() {
    return Stream.of(
        Arguments.of("../outside", "Read: path escapes workspace root: ../outside"),
        Arguments.of("<outside>/f", "Read: path escapes workspace root via symlink: <outside>/f"),
        Arguments.of("dangling", "Read: path traverses a dangling or looping symlink: dangling: "),
        Arguments.of("loop", "Read: path traverses a dangling or looping symlink: loop: "),
        Arguments.of("out/f", "Read: path escapes workspace root via symlink: out/f"),
        Arguments.of("missing/x", "Read: parent directory does not exist: ./missing/x"),
        Arguments.of("", "Read: 'path' is required"),
        Arguments.of(42, "Read: 'path' must be a string"));
  }

  @ParameterizedTest(name = "Read {0}")
  @MethodSource("unresolvablePaths")
  void anUnresolvablePathIsRefusedBeforeTheApproverIsAsked(Object path, String message) {
    var askEveryRead =
        policy(PermissionMode.DEFAULT, PermissionRule.any(PermissionEffect.ASK, ReadTool.NAME));

    var run =
        run(
            sessions.options(askEveryRead),
            denyingQuestions(),
            call(ReadTool.NAME, Map.of("path", spelled(path))));

    assertRefusedWith((String) spelled(message), run);
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals(0, sessions.readRuns.get());
  }

  @ParameterizedTest(name = "Read {0}")
  @MethodSource("unresolvablePaths")
  void anUnresolvablePathIsRefusedWithNoPolicySet(Object path, String message) {
    var run = run(sessions.withoutPolicy(), call(ReadTool.NAME, Map.of("path", spelled(path))));

    assertRefusedWith((String) spelled(message), run);
    assertEquals(0, sessions.readRuns.get());
  }

  private static void assertRefusedWith(String message, WriteToolSessions.Run run) {
    var outcome = (ToolOutcome.Rejected) run.onlyResult().outcome();
    assertEquals("Permission", outcome.by());
    assertTrue(outcome.reason().startsWith(message), outcome.reason());
    assertEquals(outcome.reason(), run.onlyBlock().reason());
  }

  @Test
  void withNoPolicyTheCanonicalCallIsAnnouncedAndRun() {
    var run = run(sessions.withoutPolicy(), read("docs/../docs/a.txt"));

    var announced = run.events().eventsOf(QueryEvent.ToolUse.class).getFirst().call();
    assertEquals(new ToolCall("c1", ReadTool.NAME, Map.of("path", "./docs/a.txt")), announced);
    assertTrue(output(run).contains("alpha"), output(run));
    assertEquals(announced, run.events().eventsOf(QueryEvent.ToolResult.class).getFirst().call());
  }

  @Test
  void aSecretInAnUnresolvablePathIsRedactedFromTheRefusal() {
    var secrets = new SecretRegistry();
    secrets.register("DIR", "s3cr3t-dir-name");

    var run = run(sessions.withoutPolicy().withSecretRegistry(secrets), read("s3cr3t-dir-name/x"));

    assertEquals(
        "Read: parent directory does not exist: ./<redacted:DIR>/x", run.onlyBlock().reason());
  }

  // ── a target swapped after approval ──────────────────────────────────────

  @Test
  void aSymlinkSwappedInWhileTheCallAwaitsApprovalIsRefusedWhenTheToolRuns() throws IOException {
    sessions.write("secrets/a.txt", PathToolSessions.SECRET);
    var permission =
        policy(
            PermissionMode.DEFAULT,
            PermissionRule.withGlob(PermissionEffect.ASK, ReadTool.NAME, "./docs/**"));
    var root = sessions.root;
    Function<AgentSession, Consumer<QueryEvent>> swapThenAllow =
        session -> {
          var allow = QuestionAnswers.selecting(session, "Allow");
          return event -> {
            if (event instanceof QueryEvent.QuestionAsked) {
              swap(root);
            }
            allow.accept(event);
          };
        };

    var run = run(sessions.options(permission), swapThenAllow, read("docs/a.txt"));

    assertEquals(
        "Read: path is not canonical or changed after it was authorised: ./docs/a.txt now"
            + " resolves to ./secrets/a.txt",
        output(run));
    assertNoSecretEmitted(run);
  }

  private static void swap(Path root) {
    try {
      Files.move(root.resolve("docs"), root.resolve("docs-aside"));
      Files.createSymbolicLink(root.resolve("docs"), root.resolve("secrets"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── presets ──────────────────────────────────────────────────────────────

  @Test
  void aReadOnlySessionReadsAFileByItsCanonicalPath() throws IOException {
    sessions.write("src/Foo.java", "class Foo {}");
    var options = SessionOptions.newBuilder().withPreset(SessionPresets.readOnly(sessions.root));

    var run = run(options, read("src/Foo.java"));

    assertEquals(new ToolOutcome.Succeeded(), run.onlyResult().outcome());
    assertTrue(output(run).contains("class Foo {}"), output(run));
  }

  // ── a rule no canonical path can match ───────────────────────────────────

  static Stream<Arguments> unmatchableRules() {
    return Stream.of(
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.DENY, "Read", "secrets/**"),
            "rule Read(secrets/**) can never match: Read keys are canonical paths such as"
                + " ./src/Main.java"),
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "./a/../b"),
            "rule Read(./a/../b) can never match: Read keys are canonical paths such as"
                + " ./src/Main.java"),
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.ASK, "Read", "./docs/"),
            "rule Read(./docs/) can never match: Read keys are canonical paths such as"
                + " ./src/Main.java"),
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.DENY, "MemoryRead", "memories/**"),
            "rule MemoryRead(memories/**) can never match: MemoryRead keys are canonical paths"
                + " such as /memories/notes.md"));
  }

  private static AgentSession create(SessionOptions.Builder options) {
    return AgentSession.create(
        options.withModel(ScriptedModel.newBuilder().withTextTurn("done").build()).build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unmatchableRules")
  void aRuleThatCanNeverMatchFailsTheSessionsCreation(PermissionRule rule, String message) {
    var options = sessions.options(policy(PermissionMode.DEFAULT, rule));

    assertEquals(
        message, assertThrows(IllegalArgumentException.class, () -> create(options)).getMessage());
  }

  @Test
  void rulesForToolsWithoutAPathOrNotRegisteredAndAnAbsoluteTrustedRuleBuild() {
    var trusted = new WorkspaceRoot(sessions.root, false);
    var permission =
        policy(
            PermissionMode.DEFAULT,
            PermissionRule.withGlob(PermissionEffect.DENY, "Execute", "BASH/rm"),
            PermissionRule.withGlob(PermissionEffect.DENY, "Unregistered", "secrets/**"),
            PermissionRule.withGlob(PermissionEffect.DENY, "Read", "/etc/**"));
    var options =
        SessionOptions.newBuilder().withTools(sessions.tools(trusted)).withPermission(permission);

    try (var session = create(options)) {
      assertFalse(session.isStarted());
    }
  }

  // ── a trusted workspace ──────────────────────────────────────────────────

  private SessionOptions.Builder trustedWithExternalLink(Permission permission) throws IOException {
    var target = Files.createDirectory(tmp.resolve("ext-target"));
    Files.writeString(target.resolve("f"), "external");
    Files.createSymbolicLink(sessions.root.resolve("ext"), target);
    var options =
        SessionOptions.newBuilder()
            .withTools(sessions.tools(new WorkspaceRoot(sessions.root, false)));
    return permission == null ? options : options.withPermission(permission);
  }

  @Test
  void aTrustedWorkspaceKeysAnOutsideTargetByItsRealPathAndReadsIt() throws IOException {
    var run = run(trustedWithExternalLink(null), read("ext/f"));

    var real = tmp.toRealPath().resolve("ext-target/f").toString();
    assertEquals(
        Map.of("path", real),
        run.events().eventsOf(QueryEvent.ToolUse.class).getFirst().call().arguments());
    assertTrue(output(run).contains("external"), output(run));
  }

  @Test
  void anInRootAllowRuleDoesNotOpenAnOutsideTargetUnderLockedDown() throws IOException {
    var permission =
        policy(
            PermissionMode.LOCKED_DOWN,
            PermissionRule.withGlob(PermissionEffect.ALLOW, ReadTool.NAME, "./**"));

    var run = run(trustedWithExternalLink(permission), read("ext/f"));

    var real = tmp.toRealPath().resolve("ext-target/f").toString();
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "LOCKED_DOWN mode forbids READ by default (Read("
                + real
                + ")) — add an explicit allow rule to open this tool"),
        run.onlyResult().outcome());
  }
}

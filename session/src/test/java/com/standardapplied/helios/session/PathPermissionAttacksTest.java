/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.PathToolSessions.SECRET;
import static com.standardapplied.helios.session.PathToolSessions.assertNoSecretEmitted;
import static com.standardapplied.helios.session.PathToolSessions.call;
import static com.standardapplied.helios.session.PathToolSessions.output;
import static com.standardapplied.helios.session.PathToolSessions.policy;
import static com.standardapplied.helios.session.PathToolSessions.read;
import static com.standardapplied.helios.session.PathToolSessions.rule;
import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.PathToolSessions.ListedRule;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.memory.MemoryBackend;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Attacks on a DENY rule over {@code ./secrets/**}: respellings of the denied target, hooks that
 * rewrite the path, approvals the user refused, walks that reach the target from elsewhere, memory
 * links and listings, none of which may bring {@code TOPSECRET} or a withheld name to the model.
 */
final class PathPermissionAttacksTest {

  private static final ListedRule DENY_READ_SECRETS =
      rule(PermissionEffect.DENY, ReadTool.NAME, "./secrets/**");
  private static final ToolOutcome DENIED_KEY =
      new ToolOutcome.Rejected(
          "Permission", "deny rule Read(./secrets/**) matched Read(./secrets/key)");

  @TempDir Path tmp;

  private PathToolSessions sessions;

  @BeforeEach
  void fixture() throws IOException {
    sessions = new PathToolSessions(Files.createDirectory(tmp.resolve("ws")));
    Files.createSymbolicLink(sessions.root.resolve("docs/leak.txt"), Path.of("../secrets/key"));
    Files.createSymbolicLink(sessions.root.resolve("docs/sec"), Path.of("../secrets"));
    Files.createSymbolicLink(tmp.resolve("alias"), sessions.root);
  }

  private WriteToolSessions.Run readDenied(String path) {
    return run(sessions.options(policy(PermissionMode.DEFAULT, DENY_READ_SECRETS)), read(path));
  }

  // ── respellings of the denied file ───────────────────────────────────────

  @ParameterizedTest(name = "Read {0}")
  @ValueSource(
      strings = {
        "secrets//key",
        ".//secrets/key",
        "./././secrets/key",
        "secrets/key/",
        "docs/missing/../../secrets/key",
        "docs/../link/key",
        "docs/leak.txt",
        "docs/sec/key",
        "<tmp>/alias/secrets/key",
        "/proc/self/root<root>/secrets/key",
        "/proc/thread-self/root<root>/secrets/key",
        "<long>secrets/key"
      })
  void aFurtherSpellingOfTheDeniedFileIsRefusedUnread(String spelling) {
    var path =
        spelling
            .replace("<root>", sessions.root.toString())
            .replace("<tmp>", tmp.toString())
            .replace("<long>", "./".repeat(3000));

    var run = readDenied(path);

    assertEquals(DENIED_KEY, run.onlyResult().outcome());
    assertNoSecretEmitted(run);
    assertEquals(0, sessions.readRuns.get());
  }

  @Test
  void aDescriptorOfTheDeniedDirectoryIsRefusedUnread() throws IOException {
    try (var pinned = Files.newDirectoryStream(sessions.root.resolve("secrets"))) {
      var fd = descriptorOf(sessions.root.resolve("secrets"));

      var run = readDenied(fd + "/key");

      assertEquals(DENIED_KEY, run.onlyResult().outcome());
      assertNoSecretEmitted(run);
    }
  }

  private static Path descriptorOf(Path directory) throws IOException {
    try (var fds = Files.list(Path.of("/proc/self/fd"))) {
      return fds.filter(fd -> pointsAt(fd, directory)).findFirst().orElseThrow();
    }
  }

  private static boolean pointsAt(Path fd, Path directory) {
    try {
      return Files.readSymbolicLink(fd).equals(directory);
    } catch (IOException | UnsupportedOperationException gone) {
      return false;
    }
  }

  @ParameterizedTest(name = "Read {0}")
  @ValueSource(
      strings = {
        "secrets\\key",
        "secrets%2fkey",
        "%2e/secrets/key",
        "secrets/key\u0000",
        "secrets/key\n",
        "secrets/key ",
        "secrets /key",
        "SECRETS/key",
        "./secrets/./key/..",
        "<root>/../ws/secrets/key"
      })
  void aSpellingThatNamesAnotherTargetNeverReachesTheSecret(String spelling) {
    var run = readDenied(spelling.replace("<root>", sessions.root.toString()));

    assertNoSecretEmitted(run);
  }

  // ── hooks and approvals ──────────────────────────────────────────────────

  @Test
  void aPathAHookRewritesIsAuthorisedAsRewritten() {
    PreToolUseHook redirect =
        (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "docs/../link/key"));
    var options =
        sessions.options(policy(PermissionMode.DEFAULT, DENY_READ_SECRETS)).withHook(redirect);

    var run = run(options, read("docs/a.txt"));

    assertEquals(DENIED_KEY, run.onlyResult().outcome());
    assertNoSecretEmitted(run);
    assertEquals(0, sessions.readRuns.get());
  }

  @Test
  void aHookCannotSmuggleAPathPastCanonicalisationAsANonString() {
    PreToolUseHook smuggle =
        (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", List.of("secrets/key")));
    var options =
        sessions.options(policy(PermissionMode.DEFAULT, DENY_READ_SECRETS)).withHook(smuggle);

    var run = run(options, read("docs/a.txt"));

    assertEquals(
        new ToolOutcome.Rejected("Permission", "Read: 'path' must be a string"),
        run.onlyResult().outcome());
    assertEquals(0, sessions.readRuns.get());
  }

  @Test
  void anAskTheUserRefusedIsAskedOnTheCanonicalKeyAndNothingIsRead() {
    var askSecrets =
        policy(PermissionMode.DEFAULT, rule(PermissionEffect.ASK, ReadTool.NAME, "./secrets/**"));

    var run =
        run(
            sessions.options(askSecrets),
            session -> QuestionAnswers.selecting(session, "Deny"),
            read("docs/leak.txt"));

    var asked = run.events().eventsOf(QueryEvent.QuestionAsked.class);
    assertEquals(1, asked.size());
    assertTrue(asked.getFirst().toString().contains("./secrets/key"), asked.toString());
    assertTrue(run.onlyResult().outcome() instanceof ToolOutcome.Rejected);
    assertNoSecretEmitted(run);
    assertEquals(0, sessions.readRuns.get());
  }

  // ── walks that reach the denied directory from elsewhere ─────────────────

  @ParameterizedTest(name = "Grep {0}")
  @ValueSource(strings = {"link", "docs/sec", "docs/..", "<root>"})
  void aContentWalkStartingAtAnySpellingWithholdsWhatReadDenies(String path) {
    var run =
        run(
            sessions.options(policy(PermissionMode.DEFAULT, DENY_READ_SECRETS)),
            call(
                GrepTool.NAME,
                Map.of(
                    "pattern", "TOP", "path", path.replace("<root>", sessions.root.toString()))));

    assertNoSecretEmitted(run);
    assertTrue(output(run).endsWith("[withheld by permission policy: 1]\n"), output(run));
  }

  @ParameterizedTest(name = "{0} docs/sec")
  @ValueSource(strings = {LsTool.NAME, GlobTool.NAME, GrepTool.NAME})
  void aWalkThroughALinkToADeniedDirectoryIsRefused(String tool) {
    var permission =
        policy(PermissionMode.DEFAULT, rule(PermissionEffect.DENY, tool, "./secrets/**"));

    var run =
        run(sessions.options(permission), call(tool, Map.of("pattern", "**", "path", "docs/sec")));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule " + tool + "(./secrets/**) matched " + tool + "(./secrets)"),
        run.onlyResult().outcome());
  }

  @Test
  void aGlobPatternReachingIntoADeniedDirectoryFindsNothingThere() {
    var permission =
        policy(PermissionMode.DEFAULT, rule(PermissionEffect.DENY, GlobTool.NAME, "./secrets/**"));

    var run =
        run(sessions.options(permission), call(GlobTool.NAME, Map.of("pattern", "secrets/*")));

    assertEquals("[withheld by permission policy: 1]\n", output(run));
  }

  // ── rules that could never match ─────────────────────────────────────────

  private static AgentSession create(SessionOptions.Builder options) {
    return AgentSession.create(
        options.withModel(ScriptedModel.newBuilder().withTextTurn("done").build()).build());
  }

  @Test
  void aReadRuleThatOnlyWithholdsFromGrepIsCheckedAgainstGrepsSpace() {
    var options =
        SessionOptions.newBuilder()
            .withTools(new ToolRegistry(List.of(GrepTool.binding(sessions.workspace))))
            .withPermission(
                policy(
                    PermissionMode.DEFAULT,
                    rule(PermissionEffect.DENY, ReadTool.NAME, "secrets/**")));

    assertEquals(
        "rule Read(secrets/**) can never match: Read keys are canonical paths such as"
            + " ./src/Main.java",
        assertThrows(IllegalArgumentException.class, () -> create(options)).getMessage());
  }

  @ParameterizedTest(name = "DENY Read({0})")
  @ValueSource(strings = {"<root>/secrets/**", "<root>/**", "<root>", "/nul\u0000/**"})
  void anAbsoluteTrustedRuleInsideTheRootFailsTheSessionsCreation(String glob) {
    var spelled = glob.replace("<root>", sessions.root.toString());
    var options =
        SessionOptions.newBuilder()
            .withTools(sessions.tools(new WorkspaceRoot(sessions.root, false)))
            .withPermission(
                policy(
                    PermissionMode.DEFAULT, rule(PermissionEffect.DENY, ReadTool.NAME, spelled)));

    assertEquals(
        "rule Read("
            + spelled
            + ") can never match: Read keys are canonical paths such as"
            + " ./src/Main.java",
        assertThrows(IllegalArgumentException.class, () -> create(options)).getMessage());
  }

  // ── memory ───────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "MemoryRead {0}")
  @ValueSource(strings = {"/memories/link/key", "/memories/leak"})
  void aLinkInsideMemoryNeverReachesTheSecret(String path) throws IOException {
    sessions.write(".agent/memory/notes.md", "notes");
    var memory = sessions.root.resolve(".agent/memory");
    Files.createSymbolicLink(memory.resolve("link"), sessions.root.resolve("secrets"));
    Files.createSymbolicLink(memory.resolve("leak"), sessions.root.resolve("secrets/key"));

    var view = run(sessions.withDefaultPolicy(), call(MemoryReadTool.NAME, Map.of("path", path)));
    var list =
        run(
            sessions.withDefaultPolicy(),
            call(MemoryReadTool.NAME, Map.of("path", "/memories/", "list", true)));

    assertNoSecretEmitted(view);
    assertEquals("/memories/notes.md\n", output(list));
  }

  @Test
  void aMemoryDirectoryReachedThroughALinkedAncestorIsRefused() throws IOException {
    Files.createSymbolicLink(sessions.root.resolve(".agent"), sessions.root.resolve("secrets"));
    Files.createDirectory(sessions.root.resolve("secrets/memory"));
    Files.writeString(sessions.root.resolve("secrets/memory/key.md"), SECRET);

    var view =
        run(
            sessions.withDefaultPolicy(),
            call(MemoryReadTool.NAME, Map.of("path", "/memories/key.md")));
    var list =
        run(
            sessions.withDefaultPolicy(),
            call(MemoryReadTool.NAME, Map.of("path", "/memories/", "list", true)));

    assertNoSecretEmitted(view);
    assertNoSecretEmitted(list);
    assertFalse(output(list).contains("key.md"), output(list));
  }

  @ParameterizedTest(name = "a backend listing {0}")
  @ValueSource(
      strings = {"/memories/./locked/a.md", "/memories//locked/a.md", "/memories/x/../locked/a.md"})
  void aMemoryListingWithholdsAnEntryTheBackendSpellsNonCanonically(String listed) {
    var permission =
        policy(
            PermissionMode.DEFAULT,
            rule(PermissionEffect.DENY, MemoryReadTool.NAME, "/memories/locked/*"));
    var options =
        SessionOptions.newBuilder()
            .withTools(new ToolRegistry(List.of()))
            .withMemoryBackend(listing(List.of("/memories/notes.md", listed)))
            .withPermission(permission);

    var run = run(options, call(MemoryReadTool.NAME, Map.of("path", "/memories/", "list", true)));

    assertEquals("/memories/notes.md\n[withheld by permission policy: 1]\n", output(run));
  }

  @ParameterizedTest(name = "a backend listing {0}")
  @ValueSource(strings = {"/elsewhere/x.md", "/memories/../x.md"})
  void aListedEntryThatIsNoMemoryPathIsMatchedAsListed(String listed) {
    var permission =
        policy(PermissionMode.DEFAULT, rule(PermissionEffect.DENY, MemoryReadTool.NAME, "**/x.md"));
    var options =
        SessionOptions.newBuilder()
            .withTools(new ToolRegistry(List.of()))
            .withMemoryBackend(listing(List.of("/memories/notes.md", listed)))
            .withPermission(permission);

    var run = run(options, call(MemoryReadTool.NAME, Map.of("path", "/memories/", "list", true)));

    assertEquals("/memories/notes.md\n[withheld by permission policy: 1]\n", output(run));
  }

  private static MemoryBackend listing(List<String> entries) {
    return new MemoryBackend() {
      @Override
      public String view(String path) {
        throw new UnsupportedOperationException(path);
      }

      @Override
      public List<String> list(String prefix) {
        return entries;
      }

      @Override
      public void create(String path, String content) {
        throw new UnsupportedOperationException(path);
      }

      @Override
      public void strReplace(String path, String oldString, String newString) {
        throw new UnsupportedOperationException(path);
      }

      @Override
      public void insert(String path, int lineNumber, String content) {
        throw new UnsupportedOperationException(path);
      }

      @Override
      public void delete(String path) {
        throw new UnsupportedOperationException(path);
      }
    };
  }
}

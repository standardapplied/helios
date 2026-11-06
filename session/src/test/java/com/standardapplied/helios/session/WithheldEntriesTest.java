/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.PathToolSessions.SECRET;
import static com.standardapplied.helios.session.PathToolSessions.assertNoSecretEmitted;
import static com.standardapplied.helios.session.PathToolSessions.call;
import static com.standardapplied.helios.session.PathToolSessions.output;
import static com.standardapplied.helios.session.PathToolSessions.policy;
import static com.standardapplied.helios.session.PathToolSessions.rule;
import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.PathToolSessions.ListedRule;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.test.QuestionAnswers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tool that walks below its authorised path withholds, without asking, every entry a DENY rule of
 * its own tool decides, an ASK rule of it unless the walk was approved through one, and, for {@code
 * Grep}, which returns contents, a DENY or ASK rule of {@code Read}. It never descends into a
 * withheld directory, names no withheld entry, and ends its output with how many it withheld. Only
 * rules filter, in every mode.
 */
final class WithheldEntriesTest {

  private static final String ONE_WITHHELD = "[withheld by permission policy: 1]\n";
  private static final String TWO_WITHHELD = "[withheld by permission policy: 2]\n";
  private static final String THREE_WITHHELD = "[withheld by permission policy: 3]\n";

  @TempDir Path tmp;

  private PathToolSessions sessions;

  @BeforeEach
  void fixture() throws IOException {
    sessions = new PathToolSessions(tmp);
    sessions.write("secrets/b.txt", SECRET);
    sessions.write("secrets/sub/c.txt", SECRET);
    Files.createSymbolicLink(sessions.root.resolve("docs/leak.txt"), Path.of("../secrets/key"));
    Files.createSymbolicLink(sessions.root.resolve("docs/sec"), Path.of("../secrets"));
  }

  private static ListedRule denySecrets(String tool) {
    return rule(PermissionEffect.DENY, tool, "./secrets/**");
  }

  private String runTool(Permission permission, String tool, Map<String, Object> args) {
    return output(run(sessions.options(permission), call(tool, args)));
  }

  /** The output of a call under {@code permission}, which neither asks nor shows a secret. */
  private String withheldRun(Permission permission, String tool, Map<String, Object> args) {
    var run = run(sessions.options(permission), call(tool, args));
    assertNoSecretEmitted(run);
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    return output(run);
  }

  private static Map<String, Object> grepTop() {
    return Map.of("pattern", "TOP", "path", ".");
  }

  private static Map<String, Object> grepSecrets() {
    return Map.of("pattern", "TOP", "path", "./secrets");
  }

  private static ListedRule askSecrets(String tool) {
    return rule(PermissionEffect.ASK, tool, "./secrets/**");
  }

  /**
   * A run of {@code Grep} over {@code ./secrets} under {@code permission}, answered {@code label}.
   */
  private WriteToolSessions.Run grepSecretsAnswered(Permission permission, String label) {
    var run =
        run(
            sessions.options(permission),
            session -> QuestionAnswers.selecting(session, label),
            call(GrepTool.NAME, grepSecrets()));
    assertEquals(1, run.events().eventsOf(QueryEvent.QuestionAsked.class).size());
    return run;
  }

  // ── each walking tool withholds what its own DENY rules match ────────────

  @Test
  void grepWithholdsADeniedDirectoryOnceWithoutReadingIt() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, denySecrets(GrepTool.NAME)), GrepTool.NAME, grepTop());

    assertEquals(ONE_WITHHELD, out);
  }

  @Test
  void globWithholdsADeniedDirectory() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, denySecrets(GlobTool.NAME)),
            GlobTool.NAME,
            Map.of("pattern", "**"));

    assertEquals("docs/a.txt\n" + ONE_WITHHELD, out);
  }

  @Test
  void lsWithholdsADeniedDirectoryAndALinkToIt() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, denySecrets(LsTool.NAME)), LsTool.NAME, Map.of());

    assertEquals("docs/\n" + TWO_WITHHELD, out);
  }

  @Test
  void lsWithholdsLinksWhoseTargetsAreDenied() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, denySecrets(LsTool.NAME)),
            LsTool.NAME,
            Map.of("path", "docs"));

    assertEquals("a.txt\n" + TWO_WITHHELD, out);
  }

  @Test
  void searchesNeverFollowALinkSoALinkIsNeitherReturnedNorCounted() {
    var none = Permission.empty(PermissionMode.DEFAULT);

    var grep = runTool(none, GrepTool.NAME, grepTop());
    var glob = runTool(none, GlobTool.NAME, Map.of("pattern", "**"));

    assertEquals(
        List.of(
            "secrets/b.txt:1:TOPSECRET",
            "secrets/key:1:TOPSECRET",
            "secrets/sub/c.txt:1:TOPSECRET"),
        grep.lines().sorted().toList());
    assertEquals(
        List.of("docs/a.txt", "secrets/b.txt", "secrets/key", "secrets/sub/c.txt"),
        glob.lines().sorted().toList());
  }

  @Test
  void aLinkIsSkippedBeforeTheCheckSoItsOwnDeniedPathIsNotCounted() {
    var permission =
        policy(
            PermissionMode.DEFAULT, rule(PermissionEffect.DENY, GlobTool.NAME, "./docs/leak.txt"));

    var glob = runTool(permission, GlobTool.NAME, Map.of("pattern", "**"));

    assertEquals(
        List.of("docs/a.txt", "secrets/b.txt", "secrets/key", "secrets/sub/c.txt"),
        glob.lines().sorted().toList());
  }

  @Test
  void aMemoryListingWithholdsDeniedEntries() throws IOException {
    sessions.write(".agent/memory/locked/a.md", SECRET);
    sessions.write(".agent/memory/notes.md", "notes");
    var permission =
        policy(
            PermissionMode.DEFAULT,
            rule(PermissionEffect.DENY, MemoryReadTool.NAME, "/memories/locked/**"));

    var out =
        withheldRun(permission, MemoryReadTool.NAME, Map.of("path", "/memories/", "list", true));

    assertEquals("/memories/notes.md\n" + ONE_WITHHELD, out);
  }

  // ── an ask rule of the walking tool withholds unless it approved the walk ─

  @Test
  void anAskRuleBelowTheWalkWithholdsWithoutAsking() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, askSecrets(GrepTool.NAME)), GrepTool.NAME, grepTop());

    assertEquals(ONE_WITHHELD, out);
  }

  @Test
  void aGlobAskRuleBelowTheWalkWithholdsWithoutAsking() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, askSecrets(GlobTool.NAME)),
            GlobTool.NAME,
            Map.of("pattern", "**"));

    assertEquals("docs/a.txt\n" + ONE_WITHHELD, out);
  }

  @Test
  void anLsAskRuleBelowTheListingWithholdsWithoutAsking() {
    var out =
        withheldRun(
            policy(PermissionMode.DEFAULT, askSecrets(LsTool.NAME)),
            LsTool.NAME,
            Map.of("path", "."));

    assertEquals("docs/\n" + TWO_WITHHELD, out);
  }

  @Test
  void anApprovedAskCoversItsWalk() {
    var run =
        grepSecretsAnswered(policy(PermissionMode.DEFAULT, askSecrets(GrepTool.NAME)), "Allow");

    assertEquals(
        List.of(
            "secrets/b.txt:1:TOPSECRET",
            "secrets/key:1:TOPSECRET",
            "secrets/sub/c.txt:1:TOPSECRET"),
        output(run).lines().sorted().toList());
  }

  @Test
  void aRefusedAskRunsNoWalk() {
    var run =
        grepSecretsAnswered(policy(PermissionMode.DEFAULT, askSecrets(GrepTool.NAME)), "Deny");

    assertEquals(
        new ToolOutcome.Rejected("Permission", "user denied Grep(./secrets)"),
        run.onlyResult().outcome());
    assertNoSecretEmitted(run);
  }

  @Test
  void aDenyRuleStillWithholdsInsideAnApprovedWalk() {
    var permission =
        policy(
            PermissionMode.DEFAULT,
            askSecrets(GrepTool.NAME),
            rule(PermissionEffect.DENY, GrepTool.NAME, "./secrets/key"));

    var run = grepSecretsAnswered(permission, "Allow");

    assertEquals(
        List.of(
            "[withheld by permission policy: 1]",
            "secrets/b.txt:1:TOPSECRET",
            "secrets/sub/c.txt:1:TOPSECRET"),
        output(run).lines().sorted().toList());
  }

  @Test
  void readsAskRulesStillWithholdInsideAnApprovedWalk() {
    var permission =
        policy(
            PermissionMode.DEFAULT,
            askSecrets(GrepTool.NAME),
            rule(PermissionEffect.ASK, ReadTool.NAME, "./secrets/sub/**"));

    var run = grepSecretsAnswered(permission, "Allow");

    assertEquals(
        List.of(
            "[withheld by permission policy: 1]",
            "secrets/b.txt:1:TOPSECRET",
            "secrets/key:1:TOPSECRET"),
        output(run).lines().sorted().toList());
  }

  // ── allow rules and mode defaults withhold nothing ───────────────────────

  @Test
  void allowRulesBelowTheWalkWithholdNothing() {
    var permission =
        policy(
            PermissionMode.DEFAULT,
            rule(PermissionEffect.ALLOW, GrepTool.NAME, "./secrets/**"),
            rule(PermissionEffect.ALLOW, ReadTool.NAME, "./secrets/**"));

    var grep = runTool(permission, GrepTool.NAME, grepTop());

    assertEquals(
        List.of(
            "secrets/b.txt:1:TOPSECRET",
            "secrets/key:1:TOPSECRET",
            "secrets/sub/c.txt:1:TOPSECRET"),
        grep.lines().sorted().toList());
  }

  @Test
  void aDenyRuleWithholdsUnderBypassPermissions() {
    var out =
        withheldRun(
            policy(PermissionMode.BYPASS_PERMISSIONS, denySecrets(GrepTool.NAME)),
            GrepTool.NAME,
            grepTop());

    assertEquals(ONE_WITHHELD, out);
  }

  @Test
  void theDefaultPolicyWithholdsNothing() {
    var grep = output(run(sessions.withDefaultPolicy(), call(GrepTool.NAME, grepTop())));

    assertEquals(
        List.of(
            "secrets/b.txt:1:TOPSECRET",
            "secrets/key:1:TOPSECRET",
            "secrets/sub/c.txt:1:TOPSECRET"),
        grep.lines().sorted().toList());
  }

  // ── a content walk obeys Read's deny and ask rules; a name walk does not ──

  @Test
  void grepWithholdsWhatReadsDenyRulesMatchWhileLsAndGlobListIt() {
    var options = sessions.options(policy(PermissionMode.DEFAULT, denySecrets(ReadTool.NAME)));

    var grep = run(options, call(GrepTool.NAME, grepTop()));
    var ls = run(options, call(LsTool.NAME, Map.of()));
    var glob = run(options, call(GlobTool.NAME, Map.of("pattern", "**")));

    assertEquals(ONE_WITHHELD, output(grep));
    assertNoSecretEmitted(grep);
    assertEquals("docs/\nsecrets/\nlink@\n", output(ls));
    assertTrue(output(glob).contains("secrets/sub/c.txt\n"), output(glob));
    assertFalse(output(glob).contains("withheld"), output(glob));
  }

  @Test
  void grepWithholdsWhatReadsAskRulesMatchWithoutAskingWhileLsAndGlobListIt() {
    var options =
        sessions.options(
            policy(
                PermissionMode.DEFAULT, rule(PermissionEffect.ASK, ReadTool.NAME, "./secrets/**")));

    var grep = run(options, call(GrepTool.NAME, grepTop()));
    var ls = run(options, call(LsTool.NAME, Map.of()));
    var glob = run(options, call(GlobTool.NAME, Map.of("pattern", "**")));

    assertEquals(ONE_WITHHELD, output(grep));
    assertNoSecretEmitted(grep);
    assertEquals(List.of(), grep.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals("docs/\nsecrets/\nlink@\n", output(ls));
    assertTrue(output(glob).contains("secrets/sub/c.txt\n"), output(glob));
  }

  @Test
  void grepWithholdsAnEntryReadsAskRuleMatchesDespiteANarrowerAllow() {
    var permission =
        policy(
            PermissionMode.DEFAULT,
            rule(PermissionEffect.ALLOW, ReadTool.NAME, "./secrets/b.txt"),
            rule(PermissionEffect.ASK, ReadTool.NAME, "./secrets/*"));

    var out = runTool(permission, GrepTool.NAME, grepTop());

    assertEquals(THREE_WITHHELD, out);
  }

  @Test
  void readsAskRulesWithholdUnderBypassPermissions() {
    var permission =
        policy(
            PermissionMode.BYPASS_PERMISSIONS,
            rule(PermissionEffect.ASK, ReadTool.NAME, "./secrets/**"));

    var run = run(sessions.options(permission), call(GrepTool.NAME, grepTop()));

    assertEquals(ONE_WITHHELD, output(run));
    assertNoSecretEmitted(run);
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
  }
}

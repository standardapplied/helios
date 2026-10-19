/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ToolBindingTest {

  private static Tool dummyTool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("test")
        .withExecutor((args, ctx) -> ToolResult.success("ok"))
        .build();
  }

  // ── canonical constructor validation ──────────────────────────────────────

  @Test
  void canonicalConstructorRejectsNullTool() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    null,
                    ToolCategory.READ,
                    args -> ToolPermissionKey.of("x"),
                    ctx -> true,
                    Optional.empty()));
    assertEquals("tool must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCategory() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    dummyTool("t"),
                    null,
                    args -> ToolPermissionKey.of("t"),
                    ctx -> true,
                    Optional.empty()));
    assertEquals("category must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullExtractor() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    dummyTool("t"), ToolCategory.READ, null, ctx -> true, Optional.empty()));
    assertEquals("permissionKeyExtractor must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullVisibility() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    dummyTool("t"),
                    ToolCategory.READ,
                    args -> ToolPermissionKey.of("t"),
                    null,
                    Optional.empty()));
    assertEquals("visibility must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullPathArgument() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    dummyTool("t"),
                    ToolCategory.READ,
                    args -> ToolPermissionKey.of("t"),
                    ctx -> true,
                    null));
    assertEquals("pathArgument must not be null", ex.getMessage());
  }

  // ── builder happy path ────────────────────────────────────────────────────

  @Test
  void builderMinimumProducesValidBinding() {
    var tool = dummyTool("Read");
    var binding = ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
    assertSame(tool, binding.tool());
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("Read", binding.name());
  }

  @Test
  void builderDefaultPermissionKeyExtractorUsesToolName() {
    var binding = ToolBinding.newBuilder(dummyTool("Read")).withCategory(ToolCategory.READ).build();
    var key = binding.canonicalize(call("Read", Map.of("path", "/x"))).key();
    assertEquals("Read", key.toolName());
    assertEquals("", key.canonicalArgs());
  }

  @Test
  void builderDefaultVisibilityIsAlwaysTrue() {
    var binding = ToolBinding.newBuilder(dummyTool("Read")).withCategory(ToolCategory.READ).build();
    assertTrue(binding.visible(new ToolVisibilityContext("sess", 0)));
    assertTrue(binding.visible(new ToolVisibilityContext("other", 99)));
  }

  @Test
  void builderHonorsCustomPermissionKeyExtractor() {
    var binding =
        ToolBinding.newBuilder(dummyTool("Read"))
            .withCategory(ToolCategory.READ)
            .withPermissionKeyExtractor(
                args -> new ToolPermissionKey("Read", String.valueOf(args.get("path"))))
            .build();
    var key = binding.canonicalize(call("Read", Map.of("path", "/workspace/foo.txt"))).key();
    assertEquals("/workspace/foo.txt", key.canonicalArgs());
  }

  @Test
  void builderHonorsCustomVisibility() {
    var binding =
        ToolBinding.newBuilder(dummyTool("Submit"))
            .withCategory(ToolCategory.CONTROL)
            .withVisibility(ctx -> ctx.turnIndex() < 5)
            .build();
    assertTrue(binding.visible(new ToolVisibilityContext("s", 4)));
    assertFalse(binding.visible(new ToolVisibilityContext("s", 5)));
  }

  @Test
  void buildWithoutCategoryThrows() {
    var ex =
        assertThrows(
            IllegalStateException.class, () -> ToolBinding.newBuilder(dummyTool("t")).build());
    assertTrue(ex.getMessage().startsWith("category is required"));
  }

  @Test
  void newBuilderRejectsNullTool() {
    var ex = assertThrows(NullPointerException.class, () -> ToolBinding.newBuilder(null));
    assertEquals("tool must not be null", ex.getMessage());
  }

  // ── builder setter null guards ────────────────────────────────────────────

  @Test
  void withCategoryRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> ToolBinding.newBuilder(dummyTool("t")).withCategory(null));
    assertEquals("category must not be null", ex.getMessage());
  }

  @Test
  void withPermissionKeyExtractorRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> ToolBinding.newBuilder(dummyTool("t")).withPermissionKeyExtractor(null));
    assertEquals("extractor must not be null", ex.getMessage());
  }

  @Test
  void withVisibilityRejectsNull() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> ToolBinding.newBuilder(dummyTool("t")).withVisibility(null));
    assertEquals("visibility must not be null", ex.getMessage());
  }

  // ── runtime guards ───────────────────────────────────────────────────────

  @Test
  void canonicalizeRejectsANullCall() {
    var binding = ToolBinding.newBuilder(dummyTool("t")).withCategory(ToolCategory.READ).build();
    var ex = assertThrows(NullPointerException.class, () -> binding.canonicalize(null));
    assertEquals("call must not be null", ex.getMessage());
  }

  @Test
  void canonicalizeRejectsExtractorReturningNull() {
    var binding =
        ToolBinding.newBuilder(dummyTool("t"))
            .withCategory(ToolCategory.READ)
            .withPermissionKeyExtractor(args -> null)
            .build();
    var ex =
        assertThrows(NullPointerException.class, () -> binding.canonicalize(call("t", Map.of())));
    assertEquals("permissionKeyExtractor returned null", ex.getMessage());
  }

  // ── path arguments ───────────────────────────────────────────────────────

  /** A space that spells a path by prefixing it with {@code ./} and refuses {@code bad}. */
  private static final PathSpace DOTTED =
      new PathSpace() {
        @Override
        public String canonical(String requested) {
          if (requested.equals("bad")) {
            throw new IllegalStateException("no such target: bad");
          }
          return requested.startsWith("./") ? requested : "./" + requested;
        }

        @Override
        public boolean admits(String glob) {
          return true;
        }

        @Override
        public String example() {
          return "./x";
        }
      };

  private static ToolBinding pathTool(ToolBinding.PathArgument path) {
    return ToolBinding.newBuilder(dummyTool("P"))
        .withCategory(ToolCategory.READ)
        .withPathArgument(path)
        .build();
  }

  private static ToolCall call(String name, Map<String, Object> args) {
    return new ToolCall("call-1", name, args);
  }

  @Test
  void aCallWithoutAPathArgumentIsUnchanged() {
    var binding = ToolBinding.newBuilder(dummyTool("t")).withCategory(ToolCategory.READ).build();
    var call = call("t", Map.of("path", "a"));
    assertSame(call, binding.canonicalize(call).call());
  }

  @Test
  void aPathArgumentIsReplacedByItsCanonicalSpellingAndKeysTheCall() {
    var binding = pathTool(ToolBinding.PathArgument.required("path", DOTTED));
    var canonical = binding.canonicalize(call("P", Map.of("path", "a/b", "limit", 3)));
    assertEquals(
        new ToolBinding.CanonicalCall(
            call("P", Map.of("path", "./a/b", "limit", 3)), new ToolPermissionKey("P", "./a/b")),
        canonical);
  }

  @Test
  void anExplicitExtractorReadsTheCanonicalArguments() {
    var binding =
        ToolBinding.newBuilder(dummyTool("P"))
            .withCategory(ToolCategory.READ)
            .withPermissionKeyExtractor(
                args -> new ToolPermissionKey("P", "key:" + args.get("path")))
            .withPathArgument(ToolBinding.PathArgument.required("path", DOTTED))
            .build();
    assertEquals(
        new ToolPermissionKey("P", "key:./a"),
        binding.canonicalize(call("P", Map.of("path", "a"))).key());
  }

  @Test
  void anAbsentOrEmptyOptionalPathTakesItsDefault() {
    var binding = pathTool(ToolBinding.PathArgument.optional("path", "d", DOTTED));
    assertEquals(
        new ToolPermissionKey("P", "./d"), binding.canonicalize(call("P", Map.of())).key());
    assertEquals(
        new ToolPermissionKey("P", "./d"),
        binding.canonicalize(call("P", Map.of("path", ""))).key());
  }

  @Test
  void aJsonNullPathIsAbsent() {
    var args = new HashMap<String, Object>();
    args.put("path", null);
    var binding = pathTool(ToolBinding.PathArgument.optional("path", "d", DOTTED));
    assertEquals(new ToolPermissionKey("P", "./d"), binding.canonicalize(call("P", args)).key());
  }

  @Test
  void anAbsentOrEmptyRequiredPathIsRefused() {
    var binding = pathTool(ToolBinding.PathArgument.required("path", DOTTED));
    var absent =
        assertThrows(
            IllegalArgumentException.class, () -> binding.canonicalize(call("P", Map.of())));
    assertEquals("'path' is required", absent.getMessage());
    var empty =
        assertThrows(
            IllegalArgumentException.class,
            () -> binding.canonicalize(call("P", Map.of("path", ""))));
    assertEquals("'path' is required", empty.getMessage());
  }

  @Test
  void aNonStringPathIsRefused() {
    var binding = pathTool(ToolBinding.PathArgument.optional("path", ".", DOTTED));
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> binding.canonicalize(call("P", Map.of("path", 42))));
    assertEquals("'path' must be a string", ex.getMessage());
  }

  @Test
  void whatTheSpaceThrowsPropagates() {
    var binding = pathTool(ToolBinding.PathArgument.required("path", DOTTED));
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> binding.canonicalize(call("P", Map.of("path", "bad"))));
    assertEquals("no such target: bad", ex.getMessage());
  }

  @Test
  void pathArgumentFactoriesDeclareNoOtherToolsDenyRules() {
    assertEquals(
        new ToolBinding.PathArgument("path", Optional.empty(), DOTTED, Set.of()),
        ToolBinding.PathArgument.required("path", DOTTED));
    assertEquals(
        new ToolBinding.PathArgument("path", Optional.of("."), DOTTED, Set.of()),
        ToolBinding.PathArgument.optional("path", ".", DOTTED));
  }

  @Test
  void withDenyRulesOfNamesTheOtherTools() {
    var path = ToolBinding.PathArgument.optional("path", ".", DOTTED).withDenyRulesOf("Read");
    assertEquals(
        new ToolBinding.PathArgument("path", Optional.of("."), DOTTED, Set.of("Read")), path);
  }

  @Test
  void pathArgumentRejectsABlankName() {
    assertThrows(
        IllegalArgumentException.class, () -> ToolBinding.PathArgument.required(" ", DOTTED));
  }

  @Test
  void pathArgumentRejectsNullComponents() {
    var noDefault =
        assertThrows(
            NullPointerException.class,
            () -> new ToolBinding.PathArgument("path", null, DOTTED, Set.of()));
    assertEquals("defaultValue must not be null", noDefault.getMessage());
    var noSpace =
        assertThrows(
            NullPointerException.class, () -> ToolBinding.PathArgument.required("path", null));
    assertEquals("space must not be null", noSpace.getMessage());
  }

  @Test
  void builderRejectsANullPathArgument() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> ToolBinding.newBuilder(dummyTool("t")).withPathArgument(null));
    assertEquals("pathArgument must not be null", ex.getMessage());
  }

  @Test
  void canonicalCallRejectsNullComponents() {
    var call = call("t", Map.of());
    assertEquals(
        "call must not be null",
        assertThrows(
                NullPointerException.class,
                () -> new ToolBinding.CanonicalCall(null, ToolPermissionKey.of("t")))
            .getMessage());
    assertEquals(
        "key must not be null",
        assertThrows(NullPointerException.class, () -> new ToolBinding.CanonicalCall(call, null))
            .getMessage());
  }

  @Test
  void isVisibleRejectsNullCtx() {
    var binding = ToolBinding.newBuilder(dummyTool("t")).withCategory(ToolCategory.READ).build();
    var ex = assertThrows(NullPointerException.class, () -> binding.visible(null));
    assertEquals("ctx must not be null", ex.getMessage());
  }

  // ── record accessors ─────────────────────────────────────────────────────

  @Test
  void accessorsExposeAllFields() {
    var binding = ToolBinding.newBuilder(dummyTool("Read")).withCategory(ToolCategory.READ).build();
    assertNotNull(binding.tool());
    assertNotNull(binding.category());
    assertNotNull(binding.permissionKeyExtractor());
    assertNotNull(binding.visibility());
  }
}

/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import java.util.Map;
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
                    null, ToolCategory.READ, args -> ToolPermissionKey.of("x"), ctx -> true));
    assertEquals("tool must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullCategory() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    dummyTool("t"), null, args -> ToolPermissionKey.of("t"), ctx -> true));
    assertEquals("category must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullExtractor() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new ToolBinding(dummyTool("t"), ToolCategory.READ, null, ctx -> true));
    assertEquals("permissionKeyExtractor must not be null", ex.getMessage());
  }

  @Test
  void canonicalConstructorRejectsNullVisibility() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () ->
                new ToolBinding(
                    dummyTool("t"), ToolCategory.READ, args -> ToolPermissionKey.of("t"), null));
    assertEquals("visibility must not be null", ex.getMessage());
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
    var key = binding.permissionKey(Map.of("path", "/x"));
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
    var key = binding.permissionKey(Map.of("path", "/workspace/foo.txt"));
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
  void permissionKeyRejectsNullArgs() {
    var binding = ToolBinding.newBuilder(dummyTool("t")).withCategory(ToolCategory.READ).build();
    var ex = assertThrows(NullPointerException.class, () -> binding.permissionKey(null));
    assertEquals("arguments must not be null", ex.getMessage());
  }

  @Test
  void permissionKeyRejectsExtractorReturningNull() {
    var binding =
        ToolBinding.newBuilder(dummyTool("t"))
            .withCategory(ToolCategory.READ)
            .withPermissionKeyExtractor(args -> null)
            .build();
    var ex = assertThrows(NullPointerException.class, () -> binding.permissionKey(Map.of()));
    assertEquals("permissionKeyExtractor returned null", ex.getMessage());
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

/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolRegistryTest {

  private static Tool dummyTool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("test")
        .withExecutor((args, ctx) -> ToolResult.success("ok"))
        .build();
  }

  private static ToolBinding binding(String name, ToolCategory category) {
    return ToolBinding.newBuilder(dummyTool(name)).withCategory(category).build();
  }

  // ── construction ──────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullList() {
    var ex = assertThrows(NullPointerException.class, () -> new ToolRegistry(null));
    assertEquals("bindings must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsListContainingNull() {
    var list = new ArrayList<ToolBinding>();
    list.add(null);
    var ex = assertThrows(NullPointerException.class, () -> new ToolRegistry(list));
    assertEquals("bindings must not contain null", ex.getMessage());
  }

  @Test
  void constructorRejectsDuplicateToolNames() {
    var a = binding("Read", ToolCategory.READ);
    var b = binding("Read", ToolCategory.WRITE);
    var ex = assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(a, b)));
    assertTrue(ex.getMessage().contains("duplicate tool name"));
    assertTrue(ex.getMessage().contains("Read"));
  }

  @Test
  void emptyFactoryProducesEmptyRegistry() {
    var registry = ToolRegistry.empty();
    assertEquals(0, registry.size());
    assertTrue(registry.bindings().isEmpty());
    assertTrue(registry.tools().isEmpty());
  }

  // ── lookup ────────────────────────────────────────────────────────────────

  @Test
  void getReturnsRegisteredBinding() {
    var read = binding("Read", ToolCategory.READ);
    var registry = new ToolRegistry(List.of(read));
    assertSame(read, registry.get("Read").orElseThrow());
  }

  @Test
  void getReturnsEmptyForUnknownName() {
    assertTrue(ToolRegistry.empty().get("nope").isEmpty());
  }

  @Test
  void getRejectsNullName() {
    var registry = ToolRegistry.empty();
    var ex = assertThrows(NullPointerException.class, () -> registry.get(null));
    assertEquals("name must not be null", ex.getMessage());
  }

  @Test
  void toolReturnsUnderlyingCoreTool() {
    var binding = binding("Read", ToolCategory.READ);
    var registry = new ToolRegistry(List.of(binding));
    assertSame(binding.tool(), registry.tool("Read").orElseThrow());
  }

  @Test
  void toolReturnsEmptyForUnknownName() {
    assertTrue(ToolRegistry.empty().tool("nope").isEmpty());
  }

  // ── snapshots ────────────────────────────────────────────────────────────

  @Test
  void bindingsSnapshotPreservesRegistrationOrder() {
    var read = binding("Read", ToolCategory.READ);
    var write = binding("Write", ToolCategory.WRITE);
    var grep = binding("Grep", ToolCategory.READ);
    var registry = new ToolRegistry(List.of(read, write, grep));
    var snap = registry.bindings();
    assertEquals(List.of("Read", "Write", "Grep"), snap.stream().map(ToolBinding::name).toList());
  }

  @Test
  void bindingsSnapshotIsImmutable() {
    var registry = new ToolRegistry(List.of(binding("Read", ToolCategory.READ)));
    var snap = registry.bindings();
    assertThrows(
        UnsupportedOperationException.class, () -> snap.add(binding("Sneaky", ToolCategory.WRITE)));
  }

  @Test
  void toolsListMirrorsBindings() {
    var read = binding("Read", ToolCategory.READ);
    var write = binding("Write", ToolCategory.WRITE);
    var registry = new ToolRegistry(List.of(read, write));
    var tools = registry.tools();
    assertEquals(List.of("Read", "Write"), tools.stream().map(Tool::name).toList());
  }

  // ── visibility filter ───────────────────────────────────────────────────

  @Test
  void visibleReturnsAllForAlwaysTruePredicates() {
    var registry =
        new ToolRegistry(
            List.of(binding("Read", ToolCategory.READ), binding("LS", ToolCategory.READ)));
    var visible = registry.visible(new ToolVisibilityContext("sess", 0));
    assertEquals(2, visible.size());
  }

  @Test
  void visibleHonorsCustomPredicate() {
    var earlyOnly =
        ToolBinding.newBuilder(dummyTool("Submit"))
            .withCategory(ToolCategory.CONTROL)
            .withVisibility(ctx -> ctx.turnIndex() < 3)
            .build();
    var always = ToolBinding.newBuilder(dummyTool("Read")).withCategory(ToolCategory.READ).build();
    var registry = new ToolRegistry(List.of(always, earlyOnly));

    var earlyVisible = registry.visible(new ToolVisibilityContext("s", 1));
    assertEquals(2, earlyVisible.size());

    var lateVisible = registry.visible(new ToolVisibilityContext("s", 5));
    assertEquals(1, lateVisible.size());
    assertEquals("Read", lateVisible.get(0).name());
  }

  @Test
  void visibleRejectsNullCtx() {
    var registry = ToolRegistry.empty();
    var ex = assertThrows(NullPointerException.class, () -> registry.visible(null));
    assertEquals("ctx must not be null", ex.getMessage());
  }

  // ── size ──────────────────────────────────────────────────────────────────

  @Test
  void sizeMatchesRegisteredCount() {
    assertEquals(0, ToolRegistry.empty().size());
    assertEquals(
        2,
        new ToolRegistry(List.of(binding("a", ToolCategory.READ), binding("b", ToolCategory.WRITE)))
            .size());
  }
}

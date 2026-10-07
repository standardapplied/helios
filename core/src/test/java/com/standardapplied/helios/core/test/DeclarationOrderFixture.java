/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.model.ToolChoice;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A conversation whose schema, tool parameters and replayed tool-call arguments each have eight or
 * more names, declared in an order that is neither alphabetical nor likely to be a hash order: an
 * output schema from a record with nested records and a list of records, two tools of which one
 * takes an array of records, and a replayed call with eight arguments. Every name is unique within
 * a request, so {@link #assertDeclaredOrder(String)} can find each by its first occurrence.
 */
public final class DeclarationOrderFixture {

  /** Where a scene lies. */
  public record Scene(double northing, double easting) {}

  /** One witness's account. */
  public record Witness(String alias, String testimony, int credibility) {}

  /** The structured answer, nine components. */
  public record Dossier(
      String rationale,
      String finding,
      String suspect,
      int docket,
      Scene scene,
      List<Witness> witnesses,
      boolean sealed,
      String detective,
      String synopsis) {}

  /** The record components of {@link Dossier}, in declaration order. */
  public static final List<String> DOSSIER_FIELDS =
      List.of(
          "rationale",
          "finding",
          "suspect",
          "docket",
          "scene",
          "witnesses",
          "sealed",
          "detective",
          "synopsis");

  /** The parameters of the {@code file_report} tool, in declaration order. */
  public static final List<String> REPORT_PARAMETERS =
      List.of(
          "headline",
          "statements",
          "urgency",
          "district",
          "bureau",
          "filedYear",
          "remarks",
          "classified");

  /** The arguments of the replayed {@code lookup_case} call, in the order the model sent them. */
  public static final List<String> ARGUMENTS =
      List.of("kestrel", "badger", "osprey", "heron", "falcon", "marten", "lynx", "bison");

  private DeclarationOrderFixture() {}

  /**
   * The output schema of {@link Dossier}.
   *
   * @return the schema
   */
  public static OutputSchema<Dossier> schema() {
    return OutputSchema.of(Dossier.class);
  }

  /**
   * The two tools: {@code file_report}, one of whose parameters is an array of {@link Witness}, and
   * {@code lookup_case}.
   *
   * @return the tools
   */
  public static List<Tool> tools() {
    var report = Tool.newBuilder().withName("file_report").withDescription("Files a report");
    for (var name : REPORT_PARAMETERS) {
      var parameter = ToolParameter.newBuilder().withName(name).withDescription(name);
      if (name.equals("statements")) {
        parameter.withType(ParameterType.ARRAY).withItemsClass(Witness.class);
      }
      report.withParameter(parameter.withRequired(true).build());
    }
    var lookup =
        Tool.newBuilder()
            .withName("lookup_case")
            .withDescription("Looks a case up")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("caseRef")
                    .withDescription("The case")
                    .withRequired(true)
                    .build());
    return List.of(
        report.withExecutor(DeclarationOrderFixture::ok).build(),
        lookup.withExecutor(DeclarationOrderFixture::ok).build());
  }

  private static ToolResult ok(Map<String, Object> arguments, ToolContext context) {
    return ToolResult.success("ok");
  }

  /**
   * A tool choice requiring one of the two tools, {@code file_report} first.
   *
   * @return the tool choice
   */
  public static ToolChoice requiredTools() {
    return ToolChoice.required("file_report", "lookup_case");
  }

  /**
   * The conversation: a system prompt, a question, the assistant's {@code lookup_case} call with
   * the {@link #ARGUMENTS} and its result.
   *
   * @return the messages
   */
  public static List<Message> history() {
    var arguments = new LinkedHashMap<String, Object>();
    for (var name : ARGUMENTS) {
      arguments.put(name, name.length());
    }
    var call =
        ToolCall.newBuilder()
            .withId("call_1")
            .withName("lookup_case")
            .withArguments(arguments)
            .build();
    return List.of(
        Message.system("You are a case clerk."),
        Message.user("Open the case and file it."),
        Message.assistant("Looking it up.", List.of(call)),
        Message.tool("call_1", "lookup_case", "Case 7 is open."));
  }

  /**
   * The body of the request a model sends for the conversation with its {@link #tools()} and {@link
   * #schema()}. The stub answers with {@code reply}, which is not a {@link Dossier}, so the call
   * ends in a parse failure after the request is sent.
   *
   * @param reply the stub's reply
   * @param modelAt builds the model under test against the stub's address
   * @return the request body, exactly as sent
   */
  public static String requestBody(List<String> reply, Function<URI, Model> modelAt) {
    return ModelHarness.exchange(
            reply,
            modelAt,
            model ->
                assertThrows(
                    StructuredOutputParseException.class,
                    () -> model.chat(history(), tools(), schema())))
        .getLast()
        .body();
  }

  /**
   * Asserts that {@code text} names the {@link #DOSSIER_FIELDS}, the {@link #REPORT_PARAMETERS} and
   * the {@link #ARGUMENTS} each in declaration order: each name first occurs after the one declared
   * before it.
   *
   * @param text a request body or prompt
   */
  public static void assertDeclaredOrder(String text) {
    for (var names : List.of(DOSSIER_FIELDS, REPORT_PARAMETERS, ARGUMENTS)) {
      assertInOrder(text, names);
    }
  }

  /**
   * Asserts that {@code text} names each of {@code names}, each first occurring after the one
   * before it.
   *
   * @param text the text to search
   * @param names the names, in their expected order
   */
  public static void assertInOrder(String text, List<String> names) {
    var previous = -1;
    for (var name : names) {
      var at = text.indexOf(name);
      assertTrue(at > previous, () -> name + " is out of declared order " + names + " in " + text);
      previous = at;
    }
  }

  /**
   * The keys of {@code map}, in iteration order.
   *
   * @param map the map
   * @return its keys as a list
   */
  public static List<String> keys(Map<String, ?> map) {
    return List.copyOf(map.keySet());
  }
}

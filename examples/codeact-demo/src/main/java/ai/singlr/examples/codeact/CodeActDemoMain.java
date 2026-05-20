/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.codeact;

import ai.singlr.core.model.Model;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.gemini.GeminiModelId;
import ai.singlr.gemini.GeminiProvider;
import ai.singlr.repl.codeact.CodeActPreset;
import ai.singlr.session.AgentSession;
import ai.singlr.session.QueryEvent;
import ai.singlr.session.SessionLimits;
import ai.singlr.session.SessionOptions;
import ai.singlr.session.UserMessage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * End-to-end demo of the {@link CodeActPreset#typed} flavour against Gemini 3.1 Pro.
 *
 * <p>The scenario: hand the agent a tiny batch of raw EDC (electronic data capture) rows and ask it
 * to emit the equivalent CDISC SDTM mapping. The model writes Java inside a JShell sandbox to walk
 * the input record, look up the destination domain/variable, and assemble the answer. The typed
 * {@code runBlocking(message, schema)} path parses the model's final JSON into {@link SdtmMapping}.
 *
 * <p>Run with {@code mvn exec:java -pl examples/codeact-demo} after setting {@code GEMINI_API_KEY}.
 */
public final class CodeActDemoMain {

  /** A single observed EDC row: the source CRF field and the captured value. */
  public record EdcRow(String field, String value) {}

  /** Input bundle handed to the agent — a tiny batch of EDC rows. */
  public record EdcInput(List<EdcRow> rows) {}

  /**
   * A single SDTM target: the CDISC domain, variable, and the value to land there. {@code value}
   * stays a {@link String} (numeric measurements are preserved as the original string token) so the
   * output schema demands a JSON string and rejects unquoted numbers.
   */
  public record SdtmRow(String domain, String variable, String value) {}

  /** Output produced by the agent — one SDTM row per input EDC row. */
  public record SdtmMapping(List<SdtmRow> rows) {}

  private CodeActDemoMain() {}

  public static void main(String[] args) throws Exception {
    var apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("Set GEMINI_API_KEY to run this demo.");
      System.exit(1);
      return;
    }

    var model =
        new GeminiProvider()
            .create(
                GeminiModelId.GEMINI_3_5_FLASH.id(),
                ModelConfig.newBuilder().withApiKey(apiKey).build());

    try {
      runDemo(model);
    } finally {
      model.close();
    }
  }

  private static void runDemo(Model model) throws Exception {
    var input = sampleInput();
    System.out.println("=== CodeAct demo: EDC -> SDTM mapping ===");
    System.out.println("input rows: " + input.rows().size());
    input.rows().forEach(row -> System.out.println("  " + row));

    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .apply(CodeActPreset.typed(EdcInput.class, SdtmMapping.class, input))
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxTurns(15)
                    .withToolTimeoutDefault(Duration.ofMinutes(2))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.events().subscribe(new ConsoleEventPrinter());
      var prompt = buildPrompt();
      var mapping =
          session.runBlocking(UserMessage.text(prompt), OutputSchema.of(SdtmMapping.class));

      System.out.println();
      System.out.println("=== SDTM mapping ===");
      mapping.rows().forEach(row -> System.out.println("  " + row));
    }
  }

  private static EdcInput sampleInput() {
    return new EdcInput(
        List.of(
            new EdcRow("DEMOG.AGE", "42"),
            new EdcRow("DEMOG.SEX", "F"),
            new EdcRow("VS.SYSBP", "120")));
  }

  static String buildPrompt() {
    return """
        Map each EDC row in `rows` to its matching CDISC SDTM target.

        Important: `rows` is a `List<Object>` whose elements are `LinkedHashMap<String,Object>`\
         (NOT typed records). Read each entry as `((Map<String,Object>) row).get("field")` and\
         `((Map<String,Object>) row).get("value")`.

        Reference mappings (EDC field -> SDTM "DOMAIN.VARIABLE"):

          DEMOG.AGE   -> DM.AGE
          DEMOG.SEX   -> DM.SEX
          DEMOG.RACE  -> DM.RACE
          VS.SYSBP    -> VS.SYSBP
          VS.DIABP    -> VS.DIABP
          VS.PULSE    -> VS.PULSE

        Step 1: in one Execute(JSHELL) call, build the lookup `Map<String,String>` above, iterate\
         `rows`, derive each target as `domain = lookup.get(field).split("\\\\.")[0]` and\
         `variable = lookup.get(field).split("\\\\.")[1]`, and println a sanity-check view.

        Step 2: reply with ONLY a single JSON object of the form:

          {"rows":[{"domain":"DM","variable":"AGE","value":"42"},\
         {"domain":"DM","variable":"SEX","value":"F"}, ...]}

        Keep every `value` as the original string (do not coerce to a number). No markdown fences,\
         no commentary, no leading or trailing prose. The top-level key MUST be `rows`.\
        """;
  }

  private static final class ConsoleEventPrinter implements Flow.Subscriber<QueryEvent> {

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(QueryEvent ev) {
      switch (ev) {
        case QueryEvent.AssistantText t -> System.out.print(t.text());
        case QueryEvent.ToolUse u ->
            System.out.println(
                "\n[tool] " + u.call().name() + " " + truncate(u.call().arguments()));
        case QueryEvent.ToolResult r ->
            System.out.println(
                "[result] "
                    + r.call().name()
                    + " "
                    + (r.result().success() ? "ok" : "FAILED: " + r.result().output()));
        case QueryEvent.ToolBlocked b ->
            System.out.println("[blocked] " + b.call().name() + ": " + b.reason());
        case QueryEvent.TurnEnded te -> System.out.println("\n[turn-ended] " + te.reason());
        case QueryEvent.LoopEnded le ->
            System.out.println("[loop-ended] " + le.result().getClass().getSimpleName());
        default -> {
          // skip the chatty events
        }
      }
    }

    @Override
    public void onError(Throwable throwable) {
      System.err.println("[stream-error] " + throwable);
    }

    @Override
    public void onComplete() {
      // nothing
    }

    private static String truncate(Object value) {
      var s = String.valueOf(value);
      if (s.length() > 240) {
        return s.substring(0, 240) + "... [" + s.length() + " chars]";
      }
      return s;
    }
  }
}

/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */
package ai.singlr.examples.rlm;

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
 * End-to-end demo of the {@link CodeActPreset#withSubLm} (RLM) flavour against Gemini.
 *
 * <p>The scenario: hand the agent a hiring-style {@code criteria} string plus a list of candidate
 * {@link Profile profiles}. The agent writes Java in a JShell sandbox that fans out parallel {@code
 * predict("score this candidate", profile.bio())} calls to a cheap sub-LM (Flash), parses the
 * scores, picks the top three, and emits the typed answer via in-sandbox {@code submit(...)}. The
 * {@code OnSubmitStopHook} captures the submission and terminates the loop.
 *
 * <p>This is the canonical RLM shape: a strong main LM drives reasoning, and a cheap sub-LM is
 * called recursively to do per-item judgement that would otherwise pollute the main context.
 *
 * <p>Run with {@code mvn exec:java -pl examples/rlm-demo} after setting {@code GEMINI_API_KEY}.
 */
public final class RlmDemoMain {

  /** A single candidate: a name plus a short biography. */
  public record Profile(String name, String bio) {}

  /** Input handed to the agent — a hiring criteria string plus the candidate pool. */
  public record MatchmakingInput(String criteria, List<Profile> candidates) {}

  /** One ranked match: the candidate's name, the model's score, and a one-line reasoning. */
  public record Match(String name, int score, String reasoning) {}

  /** Output produced by the agent — a ranked list of the best matches. */
  public record RankedMatches(List<Match> matches) {}

  private RlmDemoMain() {}

  public static void main(String[] args) throws Exception {
    var apiKey = System.getenv("GEMINI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("Set GEMINI_API_KEY to run this demo.");
      System.exit(1);
      return;
    }

    var provider = new GeminiProvider();
    var config = ModelConfig.newBuilder().withApiKey(apiKey).build();
    var mainModel = provider.create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);
    var subModel = provider.create(GeminiModelId.GEMINI_3_5_FLASH.id(), config);

    try {
      runDemo(mainModel, subModel);
    } finally {
      mainModel.close();
      subModel.close();
    }
  }

  private static void runDemo(Model mainModel, Model subModel) throws Exception {
    var input = sampleInput();
    System.out.println("=== RLM demo: candidate ranking ===");
    System.out.println("criteria:   " + input.criteria());
    System.out.println("candidates: " + input.candidates().size());
    input.candidates().forEach(p -> System.out.println("  " + p.name() + " — " + p.bio()));

    var options =
        SessionOptions.newBuilder()
            .withModel(mainModel)
            .apply(
                CodeActPreset.withSubLm(
                    MatchmakingInput.class, RankedMatches.class, input, subModel))
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxTurns(10)
                    .withToolTimeoutDefault(Duration.ofMinutes(2))
                    .build())
            .build();

    try (var session = AgentSession.create(options)) {
      session.events().subscribe(new ConsoleEventPrinter());
      var prompt =
          "You are ranking candidates against a hiring criteria. The input bindings are `criteria`"
              + " (String) and `candidates` (List<Profile> with name + bio). In a single"
              + " Execute(JSHELL) call: fan out predict(\"Score this candidate from 0 to 100"
              + " against the criteria. Reply with ONLY an integer.\", criteria + \"\\n\\n\" +"
              + " c.bio()) over every candidate, parse the integer score from each reply, pick the"
              + " top three by score, and call submit(java.util.Map.of(\"matches\","
              + " java.util.List.of(java.util.Map.of(\"name\", \"...\", \"score\", 87,"
              + " \"reasoning\", \"...\"), ...))) with the ranked matches. Keep each reasoning to"
              + " one sentence.";
      var ranked =
          session.runBlocking(UserMessage.text(prompt), OutputSchema.of(RankedMatches.class));

      System.out.println();
      System.out.println("=== Ranked matches ===");
      ranked
          .matches()
          .forEach(
              m -> System.out.println("  [" + m.score() + "] " + m.name() + " — " + m.reasoning()));
    }
  }

  private static MatchmakingInput sampleInput() {
    return new MatchmakingInput(
        "Senior backend engineer for a real-time payments platform. Must know the JVM deeply,"
            + " have shipped low-latency distributed systems, and care about correctness over"
            + " velocity.",
        List.of(
            new Profile(
                "Alice",
                "Staff engineer with 12 years of Java/Kotlin experience. Built the order-matching"
                    + " engine at a top-three crypto exchange; obsessed with p99 latency and"
                    + " formal verification."),
            new Profile(
                "Bob",
                "Frontend specialist with 8 years of React. Recently picked up Node.js for"
                    + " glue services. Loves rapid prototyping and shipping daily."),
            new Profile(
                "Carla",
                "Principal engineer with 15 years on the JVM. Led the payments-ledger rewrite at"
                    + " a fintech unicorn; published two papers on consensus-protocol latency"
                    + " bounds."),
            new Profile(
                "Dan",
                "ML researcher with 4 years at a model-training lab. Strong Python; minimal"
                    + " production-systems exposure; interested in moving into backend work."),
            new Profile(
                "Eve",
                "Senior SRE with 10 years across AWS and GCP. Wrote the chaos-engineering harness"
                    + " for a global ad-exchange handling 5M events/second; comfortable in Java"
                    + " and Go.")));
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

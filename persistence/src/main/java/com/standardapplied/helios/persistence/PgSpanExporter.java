/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.persistence;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.persistence.mapper.JsonbMapper;
import com.standardapplied.helios.persistence.mapper.UsageMapper;
import com.standardapplied.helios.persistence.sql.SpanSql;
import com.standardapplied.helios.persistence.sql.TraceSql;
import com.standardapplied.helios.session.SerializedError;
import io.helidon.dbclient.DbTransaction;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An OpenTelemetry {@link SpanExporter} that stores the spans of a session's runs in the Helios
 * trace tables, for {@link PgTraceStore} to read. It stores only spans that carry {@code
 * helios.run.id}, the run they belong to, and ignores every other span. A span of the session's
 * instrumentation scope {@code com.standardapplied.helios} is Helios's own: its {@code
 * invoke_agent} span becomes the run's {@code helios_traces} row, any other its {@code
 * helios_spans} row. It reads the attributes the session sets after the OpenTelemetry GenAI
 * semantic conventions ({@code gen_ai-spans} and {@code gen_ai-agent-spans}, status Development,
 * read on 2026-11-03), plus Helios's own {@code helios.*} attributes.
 *
 * <p>A span of any other scope is a host's: one {@code helios_spans} row of the run it names, of
 * kind {@code host}, its scope stored as its {@code otel.scope.name} attribute ({@link
 * Span#scope()}). It is how a host records what it made of a run's answer on that run's trace: a
 * span opened around {@code send}, or started after it, that names the run from {@code
 * ResultMessage.runId()} and carries the host's outcome as {@code app.*} attributes and its status,
 * which {@link Trace#spans()} returns with Helios's own:
 *
 * {@snippet :
 * var span = tracer.spanBuilder("answer").startSpan();
 * try (var ignored = span.makeCurrent()) {
 *   var result = session.send(message).join();
 *   span.setAttribute("helios.run.id", result.runId());
 *   span.setAttribute("app.reply.fallback", render(result));
 * } finally {
 *   span.end();
 * }
 * }
 *
 * <p>A run's spans may arrive in any order: Helios's step spans end before its run span, so its
 * span rows arrive first and its run row last, and a host span may end before or after the run's. A
 * span row needs no other row to exist. One {@link #export} call writes its batch in one
 * transaction; a failure rolls it back, is logged as a warning naming the batch's run ids, and is
 * returned as {@link CompletableResultCode#ofFailure()}. Span names, status descriptions, text and
 * attribute values pass through the {@link PgConfig}'s redactor, and each U+0000, which Postgres
 * cannot store as text, becomes U+FFFD. Every statement is parameterized and qualified by its
 * schema. The exporter neither owns nor closes the {@code DbClient}; after {@link #shutdown()} it
 * writes nothing. Thread-safe.
 */
public final class PgSpanExporter implements SpanExporter {

  private static final Logger LOGGER = Logger.getLogger(PgSpanExporter.class.getName());

  private static final AttributeKey<String> RUN_ID = stringKey("helios.run.id");
  private static final AttributeKey<String> OPERATION = stringKey("gen_ai.operation.name");
  private static final AttributeKey<String> AGENT_NAME = stringKey("gen_ai.agent.name");
  private static final AttributeKey<String> CONVERSATION_ID = stringKey("gen_ai.conversation.id");
  private static final AttributeKey<String> REQUEST_MODEL = stringKey("gen_ai.request.model");
  private static final AttributeKey<String> INPUT_MESSAGES = stringKey("gen_ai.input.messages");
  private static final AttributeKey<String> OUTPUT_MESSAGES = stringKey("gen_ai.output.messages");
  private static final AttributeKey<Long> INPUT_TOKENS = longKey("gen_ai.usage.input_tokens");
  private static final AttributeKey<Long> OUTPUT_TOKENS = longKey("gen_ai.usage.output_tokens");
  private static final AttributeKey<Long> CACHE_READ =
      longKey("gen_ai.usage.cache_read.input_tokens");
  private static final AttributeKey<Long> CACHE_WRITE =
      longKey("gen_ai.usage.cache_write.input_tokens");
  private static final AttributeKey<Long> REASONING_TOKENS =
      longKey("gen_ai.usage.reasoning.output_tokens");
  private static final AttributeKey<Long> COST = longKey("helios.cost.micro_usd");

  private static final Set<AttributeKey<?>> SHARED_COLUMNS =
      Set.of(RUN_ID, INPUT_TOKENS, OUTPUT_TOKENS, CACHE_READ, CACHE_WRITE, REASONING_TOKENS, COST);
  private static final Set<AttributeKey<?>> RUN_COLUMNS =
      Set.of(AGENT_NAME, CONVERSATION_ID, REQUEST_MODEL, INPUT_MESSAGES, OUTPUT_MESSAGES);

  private final PgConfig config;
  private final AtomicBoolean shutdown = new AtomicBoolean();

  /**
   * Creates an exporter that writes to the trace tables of {@code config}'s schema, redacting with
   * its redactor.
   *
   * @param config the database the spans are written to; non-null
   * @throws NullPointerException if {@code config} is null
   */
  public PgSpanExporter(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (shutdown.get()) {
      return CompletableResultCode.ofFailure();
    }
    var stored = spans.stream().filter(span -> span.getAttributes().get(RUN_ID) != null).toList();
    if (stored.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    DbTransaction tx = null;
    try {
      tx = config.dbClient().transaction();
      for (var span : stored) {
        write(tx, span);
      }
      tx.commit();
      return CompletableResultCode.ofSuccess();
    } catch (RuntimeException e) {
      var runs = stored.stream().map(span -> span.getAttributes().get(RUN_ID)).distinct().toList();
      warn("the spans of runs " + config.redact(runs.toString()) + " were not stored", e);
      if (tx != null) {
        rollback(tx);
      }
      return CompletableResultCode.ofFailure();
    }
  }

  private void rollback(DbTransaction tx) {
    try {
      tx.rollback();
    } catch (RuntimeException e) {
      warn("the rollback of spans that were not stored failed", e);
    }
  }

  private void warn(String message, RuntimeException failure) {
    LOGGER.log(
        Level.WARNING,
        message,
        SerializedError.redactedForLog(
            failure, Objects.requireNonNullElse(config.redactor(), Redactor.of(Map.of()))));
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    shutdown.set(true);
    return CompletableResultCode.ofSuccess();
  }

  private void write(DbTransaction tx, SpanData span) {
    var run = isHelios(span) && "invoke_agent".equals(span.getAttributes().get(OPERATION));
    var params = run ? runRow(span) : stepRow(span);
    params.replaceAll(value -> value instanceof String text ? storable(text) : value);
    tx.dml(config.qualify(run ? TraceSql.INSERT : SpanSql.INSERT), params.toArray());
  }

  /** {@code text} with each U+0000, which Postgres text and JSONB refuse, as U+FFFD. */
  private static String storable(String text) {
    return text.replace('\u0000', '\uFFFD');
  }

  private List<Object> runRow(SpanData span) {
    var attributes = span.getAttributes();
    var usage = usage(attributes);
    var params = new ArrayList<Object>();
    params.add(attributes.get(RUN_ID));
    params.add(span.getTraceId());
    params.add(span.getSpanId());
    params.add(attributes.get(AGENT_NAME));
    params.add(utc(span.getStartEpochNanos()));
    params.add(utc(span.getEndEpochNanos()));
    params.add(error(span));
    params.add(json(others(attributes, RUN_COLUMNS)));
    params.add(messageText(attributes.get(INPUT_MESSAGES)));
    params.add(messageText(attributes.get(OUTPUT_MESSAGES)));
    params.add(attributes.get(CONVERSATION_ID));
    params.add(attributes.get(REQUEST_MODEL));
    params.add(usage != null ? usage.totalTokens() : 0);
    UsageMapper.addParams(params, usage, cost(attributes));
    return params;
  }

  /** A Helios step's row, or a host span's: kind {@code host}, its scope as an attribute. */
  private List<Object> stepRow(SpanData span) {
    var attributes = span.getAttributes();
    var helios = isHelios(span);
    var others = others(attributes, helios ? Set.of(OPERATION) : Set.of());
    if (!helios) {
      others.put(Span.SCOPE_ATTRIBUTE, span.getInstrumentationScopeInfo().getName());
    }
    var params = new ArrayList<Object>();
    params.add(span.getSpanId());
    params.add(attributes.get(RUN_ID));
    params.add(config.redact(span.getName()));
    params.add(helios ? Objects.requireNonNullElse(attributes.get(OPERATION), "hook") : Span.HOST);
    params.add(utc(span.getStartEpochNanos()));
    params.add(utc(span.getEndEpochNanos()));
    params.add(error(span));
    params.add(json(others));
    var usage = usage(attributes);
    params.add(usage != null ? usage.totalTokens() : null);
    UsageMapper.addParams(params, usage, cost(attributes));
    return params;
  }

  private String error(SpanData span) {
    var status = span.getStatus();
    return status.getStatusCode() == StatusCode.ERROR
        ? config.redact(status.getDescription())
        : null;
  }

  private static boolean isHelios(SpanData span) {
    return Span.HELIOS_SCOPE.equals(span.getInstrumentationScopeInfo().getName());
  }

  /** The span's attributes not stored in a column of their own, as strings. */
  private static Map<String, String> others(Attributes attributes, Set<AttributeKey<?>> columns) {
    var others = new LinkedHashMap<String, String>();
    attributes.forEach(
        (key, value) -> {
          if (!SHARED_COLUMNS.contains(key) && !columns.contains(key)) {
            others.put(key.getKey(), String.valueOf(value));
          }
        });
    return others;
  }

  /** {@code attributes} as JSONB text, each key and value storable and each value redacted. */
  private String json(Map<String, String> attributes) {
    var storable = new LinkedHashMap<String, String>();
    attributes.forEach((key, value) -> storable.put(storable(key), storable(value)));
    return JsonbMapper.toJsonb(config.redactValues(storable));
  }

  private String messageText(String messages) {
    return messages == null ? null : config.redact(JsonbMapper.messageText(messages));
  }

  /**
   * The usage the session's attributes describe, its input count without the cached tokens
   * OpenTelemetry's includes and its total the four counts' sum, an absent count zero; null when
   * the span carries none. A reasoning count above the output count, which a host's span may carry,
   * is cut to it, so one span cannot fail its batch.
   */
  private static Usage usage(Attributes attributes) {
    var input = attributes.get(INPUT_TOKENS);
    if (input == null) {
      return null;
    }
    var cacheWrite = count(attributes, CACHE_WRITE);
    var cacheRead = count(attributes, CACHE_READ);
    var output = count(attributes, OUTPUT_TOKENS);
    return Usage.of(
        Math.toIntExact(input - cacheWrite - cacheRead),
        output,
        cacheWrite,
        cacheRead,
        Math.min(count(attributes, REASONING_TOKENS), output));
  }

  private static int count(Attributes attributes, AttributeKey<Long> key) {
    var value = attributes.get(key);
    return value != null ? Math.toIntExact(value) : 0;
  }

  private static CostEstimate cost(Attributes attributes) {
    var cost = attributes.get(COST);
    return cost != null ? CostEstimate.ofMicroUsd(cost) : null;
  }

  private static OffsetDateTime utc(long epochNanos) {
    return OffsetDateTime.ofInstant(Instant.ofEpochSecond(0, epochNanos), ZoneOffset.UTC);
  }
}

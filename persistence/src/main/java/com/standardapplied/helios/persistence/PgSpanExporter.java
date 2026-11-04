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
 * An OpenTelemetry {@link SpanExporter} that stores a session's spans in the Helios trace tables,
 * for {@link PgTraceStore} to read: each {@code invoke_agent} span becomes one {@code
 * helios_traces} row, any other Helios span one {@code helios_spans} row. It reads the attributes
 * the session sets after the OpenTelemetry GenAI semantic conventions ({@code gen_ai-spans} and
 * {@code gen_ai-agent-spans}, status Development, read on 2026-11-03), plus Helios's own {@code
 * helios.*} attributes. It exports only Helios spans, those of the instrumentation scope {@code
 * com.standardapplied.helios} that carry {@code helios.run.id}, and ignores every other span.
 *
 * <p>A run's step spans end before its run span, so its span rows arrive first and its run row
 * last; a span row needs no other row to exist. One {@link #export} call writes its batch in one
 * transaction; a failure rolls it back, is logged as a warning naming the batch's run ids, and is
 * returned as {@link CompletableResultCode#ofFailure()}. Text and attribute values pass through the
 * {@link PgConfig}'s redactor, and each U+0000, which Postgres cannot store as text, becomes
 * U+FFFD. Every statement is parameterized and qualified by its schema. The exporter neither owns
 * nor closes the {@code DbClient}; after {@link #shutdown()} it writes nothing. Thread-safe.
 */
public final class PgSpanExporter implements SpanExporter {

  private static final Logger LOGGER = Logger.getLogger(PgSpanExporter.class.getName());

  private static final String SCOPE = "com.standardapplied.helios";

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
  private static final AttributeKey<Long> TOTAL_TOKENS = longKey("helios.usage.total_tokens");
  private static final AttributeKey<Long> COST = longKey("helios.cost.micro_usd");

  private static final Set<AttributeKey<?>> SHARED_COLUMNS =
      Set.of(RUN_ID, INPUT_TOKENS, OUTPUT_TOKENS, CACHE_READ, CACHE_WRITE, TOTAL_TOKENS, COST);
  private static final Set<AttributeKey<?>> RUN_COLUMNS =
      Set.of(AGENT_NAME, CONVERSATION_ID, REQUEST_MODEL, INPUT_MESSAGES, OUTPUT_MESSAGES);

  private final PgConfig config;
  private final AtomicBoolean shutdown = new AtomicBoolean();

  /**
   * @param config the database the spans are written to
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
    var helios = spans.stream().filter(PgSpanExporter::isHelios).toList();
    if (helios.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }
    DbTransaction tx = null;
    try {
      tx = config.dbClient().transaction();
      for (var span : helios) {
        write(tx, span);
      }
      tx.commit();
      return CompletableResultCode.ofSuccess();
    } catch (RuntimeException e) {
      var runs = helios.stream().map(span -> span.getAttributes().get(RUN_ID)).distinct().toList();
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

  private static boolean isHelios(SpanData span) {
    return SCOPE.equals(span.getInstrumentationScopeInfo().getName())
        && span.getAttributes().get(RUN_ID) != null;
  }

  private void write(DbTransaction tx, SpanData span) {
    var run = "invoke_agent".equals(span.getAttributes().get(OPERATION));
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
    params.add(others(attributes, RUN_COLUMNS));
    params.add(messageText(attributes.get(INPUT_MESSAGES)));
    params.add(messageText(attributes.get(OUTPUT_MESSAGES)));
    params.add(attributes.get(CONVERSATION_ID));
    params.add(attributes.get(REQUEST_MODEL));
    params.add(usage != null ? usage.totalTokens() : 0);
    UsageMapper.addParams(params, usage, cost(attributes));
    return params;
  }

  private List<Object> stepRow(SpanData span) {
    var attributes = span.getAttributes();
    var operation = attributes.get(OPERATION);
    var params = new ArrayList<Object>();
    params.add(span.getSpanId());
    params.add(attributes.get(RUN_ID));
    params.add(span.getName());
    params.add(operation != null ? operation : "hook");
    params.add(utc(span.getStartEpochNanos()));
    params.add(utc(span.getEndEpochNanos()));
    params.add(error(span));
    params.add(others(attributes, Set.of(OPERATION)));
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

  /** The span's attributes not stored in a column of their own, as redacted JSON strings. */
  private String others(Attributes attributes, Set<AttributeKey<?>> columns) {
    var others = new LinkedHashMap<String, String>();
    attributes.forEach(
        (key, value) -> {
          if (!SHARED_COLUMNS.contains(key) && !columns.contains(key)) {
            others.put(key.getKey(), storable(String.valueOf(value)));
          }
        });
    return JsonbMapper.toJsonb(config.redactValues(others));
  }

  private String messageText(String messages) {
    return messages == null ? null : config.redact(JsonbMapper.messageText(messages));
  }

  /**
   * The usage the session's attributes describe, its input count without the cached tokens
   * OpenTelemetry's includes and its total the four counts' sum; null when the span carries none.
   */
  private static Usage usage(Attributes attributes) {
    var input = attributes.get(INPUT_TOKENS);
    if (input == null) {
      return null;
    }
    var cacheWrite = count(attributes, CACHE_WRITE);
    var cacheRead = count(attributes, CACHE_READ);
    return Usage.of(
        Math.toIntExact(input - cacheWrite - cacheRead),
        count(attributes, OUTPUT_TOKENS),
        cacheWrite,
        cacheRead);
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

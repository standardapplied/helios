/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.runtime.Durability;
import java.util.Objects;

/**
 * One-line factory for a Postgres-backed {@link Durability} bundle.
 *
 * <pre>{@code
 * var pgConfig = PgConfig.newBuilder().withDbClient(dbClient).withSchema("lg").build();
 *
 * var agent = new Agent(AgentConfig.newBuilder()
 *     .withModel(model)
 *     .withMemory(new PgMemory(pgConfig))
 *     .withDurability(PgDurability.of(pgConfig))
 *     .build());
 * }</pre>
 *
 * <p>For sophisticated configuration (custom unsafe-resume policy, idempotency overrides), use
 * {@link Durability#newBuilder()} with {@link PgRunStore} and {@link PgToolCallJournal} constructed
 * from the same {@code PgConfig}.
 */
public final class PgDurability {

  private PgDurability() {}

  /**
   * Returns a {@link Durability} bundle wired to {@link PgRunStore} and {@link PgToolCallJournal}
   * sharing the supplied {@code PgConfig} (and therefore the same {@link
   * io.helidon.dbclient.DbClient}).
   */
  public static Durability of(PgConfig pgConfig) {
    Objects.requireNonNull(pgConfig, "pgConfig");
    return Durability.of(new PgRunStore(pgConfig), new PgToolCallJournal(pgConfig));
  }
}

/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.core.prompt.Prompt;
import ai.singlr.core.prompt.PromptRegistry;
import ai.singlr.persistence.mapper.PromptMapper;
import ai.singlr.persistence.sql.PromptSql;
import io.helidon.dbclient.DbClient;
import java.util.List;
import java.util.Objects;

/**
 * PostgreSQL-backed implementation of {@link PromptRegistry} using Helidon DbClient.
 *
 * <p>Thread safety is handled by the database and Helidon's connection pooling.
 */
public class PgPromptRegistry implements PromptRegistry {

  private final PgConfig config;
  private final DbClient dbClient;

  public PgPromptRegistry(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
  }

  @Override
  public Prompt register(String name, String content) {
    return insertVersion(name, content, true);
  }

  @Override
  public Prompt registerDraft(String name, String content) {
    return insertVersion(name, content, false);
  }

  @Override
  public Prompt activate(String name, int version) {
    var tx = dbClient.transaction();
    try {
      tx.dml(config.qualify(PromptSql.DEACTIVATE), name);
      var activatedRows = tx.dml(config.qualify(PromptSql.ACTIVATE), name, version);
      if (activatedRows == 0) {
        tx.rollback();
        throw new IllegalArgumentException("No prompt found: " + name + " v" + version);
      }
      var prompt =
          tx.query(config.qualify(PromptSql.RESOLVE_VERSION), name, version)
              .findFirst()
              .map(PromptMapper::map)
              .orElseThrow();
      tx.commit();
      return prompt;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      try {
        tx.rollback();
      } catch (Exception rollbackEx) {
        e.addSuppressed(rollbackEx);
      }
      throw new PgException("Failed to activate prompt: " + name + " v" + version, e);
    }
  }

  private Prompt insertVersion(String name, String content, boolean active) {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException("Prompt name must not be blank");
    }
    if (content == null) {
      throw new IllegalArgumentException("Prompt content must not be null");
    }

    var id = Ids.newId();
    var createdAt = Ids.now();
    var variables = Prompt.extractVariables(content);
    var variablesLiteral = PromptMapper.toArrayLiteral(variables);

    var tx = dbClient.transaction();
    try {
      var versionRow = tx.query(config.qualify(PromptSql.NEXT_VERSION), name).findFirst();
      int nextVersion = versionRow.map(r -> r.column("next_version").getInt()).orElse(1);

      if (active) {
        tx.dml(config.qualify(PromptSql.DEACTIVATE), name);
      }

      tx.dml(
          config.qualify(PromptSql.INSERT),
          id.toString(),
          name,
          content,
          nextVersion,
          active,
          variablesLiteral,
          createdAt);

      tx.commit();

      return new Prompt(id, name, content, nextVersion, active, variables, createdAt);
    } catch (Exception e) {
      try {
        tx.rollback();
      } catch (Exception rollbackEx) {
        e.addSuppressed(rollbackEx);
      }
      throw new PgException("Failed to register prompt: " + name, e);
    }
  }

  @Override
  public Prompt resolve(String name) {
    try {
      return dbClient
          .execute()
          .query(config.qualify(PromptSql.RESOLVE_ACTIVE), name)
          .findFirst()
          .map(PromptMapper::map)
          .orElse(null);
    } catch (Exception e) {
      throw new PgException("Failed to resolve prompt: " + name, e);
    }
  }

  @Override
  public Prompt resolve(String name, int version) {
    try {
      return dbClient
          .execute()
          .query(config.qualify(PromptSql.RESOLVE_VERSION), name, version)
          .findFirst()
          .map(PromptMapper::map)
          .orElse(null);
    } catch (Exception e) {
      throw new PgException("Failed to resolve prompt: " + name + " v" + version, e);
    }
  }

  @Override
  public List<Prompt> versions(String name) {
    try {
      return PromptMapper.mapAll(
          dbClient.execute().query(config.qualify(PromptSql.LIST_VERSIONS), name));
    } catch (Exception e) {
      throw new PgException("Failed to list versions: " + name, e);
    }
  }
}

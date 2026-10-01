/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - PostgreSQL Persistence Module.
 *
 * <p>Provides PostgreSQL-backed implementations of core persistence interfaces using Helidon
 * DbClient, starting with {@link com.standardapplied.helios.core.prompt.PromptRegistry}.
 */
module com.standardapplied.helios.persistence {
  requires com.standardapplied.helios.core;
  requires ai.singlr.scimsql;
  requires io.helidon.dbclient;
  requires io.helidon.common.mapper;
  requires tools.jackson.databind;
  requires java.sql;

  exports com.standardapplied.helios.persistence;

  provides io.helidon.common.mapper.spi.MapperProvider with
      com.standardapplied.helios.persistence.mapper.DbTypeMapperProvider;
}

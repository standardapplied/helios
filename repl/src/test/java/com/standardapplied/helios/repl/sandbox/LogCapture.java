/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** The records a class's logger publishes while open, as "LEVEL message". */
final class LogCapture extends Handler implements AutoCloseable {

  private final Logger logger;
  private final List<String> records = new CopyOnWriteArrayList<>();
  private final Level priorLevel;

  LogCapture(Class<?> owner) {
    this.logger = Logger.getLogger(owner.getName());
    this.priorLevel = logger.getLevel();
    logger.addHandler(this);
    logger.setLevel(Level.ALL);
  }

  List<String> records() {
    return List.copyOf(records);
  }

  @Override
  public void publish(LogRecord record) {
    records.add(record.getLevel() + " " + record.getMessage());
  }

  @Override
  public void flush() {}

  @Override
  public void close() {
    logger.removeHandler(this);
    logger.setLevel(priorLevel);
  }
}

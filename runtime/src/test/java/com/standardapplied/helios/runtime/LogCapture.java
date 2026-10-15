/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Records what one class's logger publishes, at every level, until closed. */
final class LogCapture extends Handler implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();

  private LogCapture(Logger logger) {
    this.logger = logger;
    this.previousLevel = logger.getLevel();
    setLevel(Level.ALL);
    logger.setLevel(Level.ALL);
    logger.addHandler(this);
  }

  static LogCapture of(Class<?> owner) {
    return new LogCapture(Logger.getLogger(owner.getName()));
  }

  List<LogRecord> records() {
    return List.copyOf(records);
  }

  @Override
  public void publish(LogRecord logRecord) {
    records.add(logRecord);
  }

  @Override
  public void flush() {}

  @Override
  public void close() {
    logger.removeHandler(this);
    logger.setLevel(previousLevel);
  }
}

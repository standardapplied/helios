/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects what one class's logger logs, at every level, until closed. */
final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final Level level;
  private final Handler handler;

  private LogCapture(Logger logger, List<LogRecord> records) {
    this.logger = logger;
    this.level = logger.getLevel();
    this.handler =
        new Handler() {
          @Override
          public void publish(LogRecord logRecord) {
            records.add(logRecord);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    handler.setLevel(Level.ALL);
    logger.setLevel(Level.ALL);
    logger.addHandler(handler);
  }

  static LogCapture of(Class<?> owner, List<LogRecord> records) {
    return new LogCapture(Logger.getLogger(owner.getName()), records);
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
    logger.setLevel(level);
  }
}

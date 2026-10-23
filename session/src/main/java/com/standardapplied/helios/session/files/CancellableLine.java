/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import com.standardapplied.helios.core.runtime.CancellationToken;

/**
 * A line a regex matches through, which stops the match once the call's token is cancelled: every
 * {@value #CHECK_INTERVAL}th {@link #charAt} throws the token's {@link
 * java.util.concurrent.CancellationException} if it fired, so a backtracking pattern on a long line
 * ends with its call.
 */
final class CancellableLine implements CharSequence {

  static final int CHECK_INTERVAL = 1024;

  private final CharSequence line;
  private final CancellationToken cancellation;
  private int reads;

  CancellableLine(CharSequence line, CancellationToken cancellation) {
    this.line = line;
    this.cancellation = cancellation;
  }

  @Override
  public char charAt(int index) {
    if (++reads % CHECK_INTERVAL == 0) {
      cancellation.throwIfCancelled();
    }
    return line.charAt(index);
  }

  @Override
  public int length() {
    return line.length();
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return line.subSequence(start, end);
  }

  @Override
  public String toString() {
    return line.toString();
  }
}

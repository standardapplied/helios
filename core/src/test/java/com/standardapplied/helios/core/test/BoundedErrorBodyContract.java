/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.HttpClientFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The bound on the body of a failed call that every provider reads through {@link
 * HttpClientFactory#readBoundedErrorBody}: at most 64 KB, with a marker when the server sent more.
 * A provider's model test extends this, so each provider runs these cases.
 */
public abstract class BoundedErrorBodyContract {

  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  @Test
  void readBoundedErrorBodyCapsAtLimitAndMarksTruncation() throws Exception {
    var oversized = new byte[MAX_ERROR_BODY_BYTES + 1024];
    Arrays.fill(oversized, (byte) 'x');
    var result = HttpClientFactory.readBoundedErrorBody(new ByteArrayInputStream(oversized));
    assertTrue(result.contains("[truncated"));
    assertTrue(
        result.length() <= MAX_ERROR_BODY_BYTES + 100,
        "result must be capped at MAX_ERROR_BODY_BYTES + a short truncation marker");
  }

  @Test
  void readBoundedErrorBodyReturnsExactBytesWhenUnderLimit() throws Exception {
    var msg = "compact error payload";
    var result =
        HttpClientFactory.readBoundedErrorBody(
            new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
    assertEquals(msg, result);
  }
}

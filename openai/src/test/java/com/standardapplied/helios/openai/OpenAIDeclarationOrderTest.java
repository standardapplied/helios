/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai;

import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.test.ChildJvm;
import com.standardapplied.helios.core.test.DeclarationOrderFixture;
import com.standardapplied.helios.core.test.Golden;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The request body {@link DeclarationOrderFixture}'s conversation sends: byte-identical in every
 * JVM, with its schema, tool parameters and replayed arguments in declaration order.
 */
class OpenAIDeclarationOrderTest {

  /** Prints the request body. */
  static final class Probe {
    public static void main(String[] args) {
      System.out.print(body());
    }
  }

  @Test
  void requestBodyIsTheSameInEveryJvmAndInDeclaredOrder(@TempDir Path dir) {
    DeclarationOrderFixture.assertDeclaredOrder(ChildJvm.sameInEveryJvm(dir, Probe.class));
  }

  private static String body() {
    return DeclarationOrderFixture.requestBody(
        List.of(Golden.read("openai/requests-reply.sse")),
        uri ->
            new OpenAIProvider()
                .create(
                    OpenAIModelId.GPT_4_1.id(),
                    ModelConfig.newBuilder()
                        .withApiKey("test-key")
                        .withBaseUrl(uri + "/v1/responses")
                        .withToolChoice(DeclarationOrderFixture.requiredTools())
                        .build()));
  }
}

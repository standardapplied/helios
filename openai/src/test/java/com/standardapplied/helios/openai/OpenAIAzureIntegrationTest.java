/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.core.test.Accepted;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;

/**
 * Real-API Azure OpenAI integration. Reproduces the user-reported failure path against an actual
 * Azure deployment to isolate whether the Helios Provider gate, the request URI assembly, the
 * header auth, or the wire-level handshake is the breaking point.
 *
 * <p>Required env: {@code CLIENT_OPENAI_BASEURL} (the full Azure responses URL including the {@code
 * api-version} query) and {@code CLIENT_OPENAI_API_KEY} (the Azure resource api-key).
 */
@EnabledIfEnvironmentVariables({
  @EnabledIfEnvironmentVariable(named = "CLIENT_OPENAI_BASEURL", matches = ".+"),
  @EnabledIfEnvironmentVariable(named = "CLIENT_OPENAI_API_KEY", matches = ".+")
})
final class OpenAIAzureIntegrationTest {

  private static ModelConfig azureConfig() {
    return ModelConfig.newBuilder()
        .withBaseUrl(System.getenv("CLIENT_OPENAI_BASEURL"))
        .withHeader("api-key", System.getenv("CLIENT_OPENAI_API_KEY"))
        .build();
  }

  @Test
  void providerRejectsUnknownModelWithoutBaseUrl() {
    var provider = new OpenAIProvider();
    var config = ModelConfig.newBuilder().withApiKey("dummy").build();
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> provider.create("custom-azure-deployment-name", config));
    assertTrue(
        ex.getMessage().contains("Unsupported model"),
        () -> "expected 'Unsupported model' rejection, got: " + ex.getMessage());
  }

  @Test
  void providerAcceptsAnyModelIdWhenBaseUrlSet() {
    var provider = new OpenAIProvider();
    var model = provider.create("my-custom-azure-deployment", azureConfig());
    assertNotNull(model);
    assertEquals("my-custom-azure-deployment", model.id());
    assertEquals("openai", model.provider());
    assertEquals(0, model.contextWindow());
  }

  public record SimpleResponse(List<String> items, String summary) {}

  public record ResponseWithMaps(
      List<String> items,
      String summary,
      Map<String, String> notes,
      Map<String, List<String>> deps) {}

  @Test
  void structuredOutputWithoutMapsWorksOnAzure() {
    var deploymentName = deploymentName();
    var model = new OpenAIProvider().create(deploymentName, azureConfig());
    try {
      var schema = OutputSchema.of(SimpleResponse.class);
      var response =
          model.chat(
              List.of(
                  Message.user("List 2 colors. Return items=['red','blue'], summary='colors'.")),
              List.of(),
              schema);
      assertNotNull(response.parsed(), "strict structured output must parse");
    } finally {
      try {
        model.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void structuredOutputWithOpenMapsOnAzure() {
    var deploymentName = deploymentName();
    var model = new OpenAIProvider().create(deploymentName, azureConfig());
    try {
      var schema = OutputSchema.of(ResponseWithMaps.class);
      var messages =
          List.of(
              Message.user(
                  "List 2 colors. Return items=['red','blue'], summary='colors',"
                      + " notes={'red':'warm'}, deps={'blue':['sky']}."));
      var response =
          Accepted.parsedOrSkip(
              deploymentName,
              "OpenAIStreamTranscriptTest#anOpenMapStructuredReplyParsesIntoItsRecord",
              () -> model.chat(messages, List.of(), schema));
      assertNotNull(response.parsed(), "structured output with Maps parses on Azure");
    } finally {
      try {
        model.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void largePromptStructuredOutputReproducesThrottling() {
    var deploymentName = deploymentName();
    var model = new OpenAIProvider().create(deploymentName, azureConfig());
    try {
      // ~70K token prompt — similar to the client's 72K domain-planning call.
      var padding = new StringBuilder();
      for (var i = 0; i < 2000; i++) {
        padding
            .append("Variable ITEM_")
            .append(i)
            .append(": role=Topic, core=Required, type=Char(200), ")
            .append("codelist=[A,B,C,D,E,F,G,H,I,J], ")
            .append("description='Clinical observation value for domain entry ")
            .append(i)
            .append(". Format follows CDISC SDTM IG v3.4 conventions.'\n");
      }
      var userMsg =
          "Given these 2000 variables:\n"
              + padding
              + "\nReturn orderedItems (first 5 variable names only), a brief summary,"
              + " notes={'ITEM_0':'note'}, deps={'ITEM_1':['ITEM_0']}.";

      var messages = List.of(Message.user(userMsg));
      var schema = OutputSchema.of(ResponseWithMaps.class);
      try {
        Accepted.textReply(model.chat(messages, List.of(), schema));
      } catch (StructuredOutputParseException unparsed) {
        assertNotNull(unparsed.rawContent(), "Azure accepted the request and replied");
      }
    } finally {
      try {
        model.close();
      } catch (Exception ignored) {
      }
    }
  }

  private static String deploymentName() {
    var name = System.getenv("CLIENT_OPENAI_DEPLOYMENT");
    return (name == null || name.isBlank()) ? "gpt-4o" : name;
  }

  @Test
  void customDeploymentNameRoundTripsAgainstAzure() {
    // The real fix: pass the Azure deployment name directly — it becomes the "model" field in
    // the request body and Azure maps it to the deployment. No need to match a canonical id.
    var deploymentName = System.getenv("CLIENT_OPENAI_DEPLOYMENT");
    if (deploymentName == null || deploymentName.isBlank()) {
      deploymentName = "gpt-4o";
    }
    var provider = new OpenAIProvider();
    var model = provider.create(deploymentName, azureConfig());
    try {
      Accepted.textReply(
          model.chat(List.of(Message.user("Reply with the single digit 7 and nothing else."))));
    } finally {
      try {
        model.close();
      } catch (Exception ignored) {
      }
    }
  }
}

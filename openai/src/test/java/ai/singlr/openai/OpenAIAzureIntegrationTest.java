/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.schema.OutputSchema;
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
      assertNotNull(response.parsed(), "structured output must parse");
      assertFalse(response.parsed().items().isEmpty());
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
      var response =
          model.chat(
              List.of(
                  Message.user(
                      "List 2 colors. Return items=['red','blue'], summary='colors',"
                          + " notes={'red':'warm'}, deps={'blue':['sky']}.")),
              List.of(),
              schema);
      assertNotNull(response.parsed(), "structured output with Maps must parse on Azure");
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

      System.out.println(
          "Prompt size: ~" + (userMsg.length() / 4) + " tokens. Sending to Azure...");
      var start = System.currentTimeMillis();
      var schema = OutputSchema.of(ResponseWithMaps.class);
      var response = model.chat(List.of(Message.user(userMsg)), List.of(), schema);
      var elapsed = (System.currentTimeMillis() - start) / 1000.0;
      System.out.println("Completed in " + elapsed + "s");
      assertNotNull(response.parsed(), "structured output must parse even with large prompt");
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
      var response =
          model.chat(List.of(Message.user("Reply with the single digit 7 and nothing else.")));
      assertNotNull(response);
      assertNotNull(response.content());
      assertFalse(
          response.content().isBlank(),
          () -> "Azure returned blank content; full response: " + response);
      assertEquals(FinishReason.STOP, response.finishReason());
    } finally {
      try {
        model.close();
      } catch (Exception ignored) {
      }
    }
  }
}

/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.test;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import java.util.List;

/** Shared test utility that returns a canned response and captures the last messages sent. */
public class MockModel implements Model {
  private final String response;
  private List<Message> lastMessages;

  public MockModel(String response) {
    this.response = response;
  }

  @Override
  public Response<Void> chat(List<Message> messages, List<Tool> tools) {
    this.lastMessages = messages;
    return Response.newBuilder().withContent(response).withFinishReason(FinishReason.STOP).build();
  }

  @Override
  public String id() {
    return "mock";
  }

  @Override
  public String provider() {
    return "test";
  }

  public List<Message> lastMessages() {
    return lastMessages;
  }
}

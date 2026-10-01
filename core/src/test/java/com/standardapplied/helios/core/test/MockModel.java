/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.tool.Tool;
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

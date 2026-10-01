/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FileReference;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.InlineFile;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.Response.Usage;
import ai.singlr.core.model.Role;
import ai.singlr.core.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 3 acceptance test: a UserMessage carrying file attachments (image + PDF + CSV) reaches the
 * provider as {@code Message.inlineFiles}, intact.
 *
 * <p>The agent loop is exercised end-to-end with a recording stub Model. We assert the Model
 * observed the attachment bytes and media types verbatim — proving the path from {@link
 * UserMessage.Builder#withAttachment} through {@link ai.singlr.session.loop.AgentLoop} to {@link
 * Message} works without provider involvement.
 */
final class Phase3AcceptanceTest {

  private static final class RecordingModel implements Model {
    final List<Message> observedMessages = new CopyOnWriteArrayList<>();

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      observedMessages.addAll(messages);
      return Response.newBuilder()
          .withContent("ok, I see your files")
          .withFinishReason(FinishReason.STOP)
          .withUsage(Usage.of(1, 1))
          .build();
    }

    @Override
    public String id() {
      return "phase3-stub";
    }

    @Override
    public String provider() {
      return "test";
    }
  }

  @Test
  void userMessageAttachmentsReachTheModel(@TempDir Path tmp) throws Exception {
    var pngPath = tmp.resolve("diagram.png");
    var pdfPath = tmp.resolve("report.pdf");
    var csvPath = tmp.resolve("data.csv");
    Files.write(pngPath, new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
    Files.write(pdfPath, "%PDF-1.4\nstub\n".getBytes(StandardCharsets.UTF_8));
    Files.writeString(csvPath, "a,b,c\n1,2,3\n", StandardCharsets.UTF_8);

    var model = new RecordingModel();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("phase3-" + UUID.randomUUID())
            .build();

    var userMessage =
        UserMessage.newBuilder()
            .withText("What do these show?")
            .withAttachment(pngPath)
            .withAttachment(pdfPath)
            .withAttachment(csvPath)
            .build();

    try (var session = AgentSession.create(options)) {
      var result = session.runBlocking(userMessage);
      assertInstanceOf(ResultMessage.Success.class, result);
      assertTrue(session.result().isDone());
      session.result().get(2, TimeUnit.SECONDS);
    }

    // Find the user message the model saw.
    var userObserved =
        model.observedMessages.stream()
            .filter(m -> m.role() == Role.USER)
            .findFirst()
            .orElseThrow();
    assertEquals("What do these show?", userObserved.content());
    assertTrue(userObserved.hasInlineFiles(), "user message should carry inline files");

    var attachmentMediaTypes = new ArrayList<String>();
    for (var f : userObserved.inlineFiles()) {
      attachmentMediaTypes.add(f.mimeType());
    }
    assertEquals(3, userObserved.inlineFiles().size());
    assertTrue(attachmentMediaTypes.contains("image/png"));
    assertTrue(attachmentMediaTypes.contains("application/pdf"));
    assertTrue(attachmentMediaTypes.contains("text/csv"));

    // Verify the exact bytes round-trip.
    var pngObserved =
        userObserved.inlineFiles().stream()
            .filter(f -> "image/png".equals(f.mimeType()))
            .findFirst()
            .orElseThrow();
    assertEquals(InlineFile.of(Files.readAllBytes(pngPath), "image/png"), pngObserved);
  }

  @Test
  void textOnlyMessagesYieldEmptyInlineFiles() throws Exception {
    var model = new RecordingModel();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("phase3-plain-" + UUID.randomUUID())
            .build();

    try (var session = AgentSession.create(options)) {
      session.runBlocking(UserMessage.text("plain text"));
    }

    var userObserved =
        model.observedMessages.stream()
            .filter(m -> m.role() == Role.USER)
            .findFirst()
            .orElseThrow();
    assertEquals(0, userObserved.inlineFiles().size());
  }

  @Test
  void fileReferenceReachesTheModel() throws Exception {
    var model = new RecordingModel();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("phase3-reference-" + UUID.randomUUID())
            .build();
    var reference = FileReference.of("https://example.com/video.mp4", "video/mp4");

    try (var session = AgentSession.create(options)) {
      session.runBlocking(
          UserMessage.newBuilder()
              .withText("Summarize this video")
              .withFileReference(reference)
              .build());
    }

    var userObserved =
        model.observedMessages.stream()
            .filter(m -> m.role() == Role.USER)
            .findFirst()
            .orElseThrow();
    assertEquals(List.of(reference), userObserved.fileReferences());
  }

  @Test
  void attachmentOnlyMessageWorks(@TempDir Path tmp) throws IOException {
    Files.write(tmp.resolve("p.png"), new byte[] {1, 2, 3});
    var model = new RecordingModel();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSessionId("phase3-att-only-" + UUID.randomUUID())
            .build();

    try (var session = AgentSession.create(options)) {
      var msg = UserMessage.newBuilder().withAttachment(tmp.resolve("p.png")).build();
      session.runBlocking(msg);
    }

    var userObserved =
        model.observedMessages.stream()
            .filter(m -> m.role() == Role.USER)
            .findFirst()
            .orElseThrow();
    assertEquals(1, userObserved.inlineFiles().size());
    // Empty text gets composed empty.
    assertEquals("", userObserved.content());
  }
}

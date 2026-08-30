/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FileReference;
import ai.singlr.core.model.InlineFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UserMessageTest {

  @Test
  void textAccessorReturnsConstructedValue() {
    var msg = UserMessage.text("hello there");
    assertEquals("hello there", msg.text());
    assertTrue(msg.attachments().isEmpty());
    assertFalse(msg.hasAttachments());
  }

  @Test
  void canonicalConstructorAcceptsTextAndEmptyAttachments() {
    var msg = new UserMessage("hi", List.of());
    assertEquals("hi", msg.text());
  }

  @Test
  void canonicalConstructorAcceptsBlankTextWhenAttachmentsPresent() {
    var attachment = InlineFile.of(new byte[] {1, 2}, "image/png");
    var msg = new UserMessage("", List.of(attachment));
    assertEquals("", msg.text());
    assertEquals(1, msg.attachments().size());
    assertTrue(msg.hasAttachments());
  }

  @Test
  void factoryProducesEqualRecord() {
    assertEquals(new UserMessage("hi", List.of()), UserMessage.text("hi"));
  }

  @Test
  void recordsWithSameFieldsAreEqual() {
    var a = UserMessage.text("same");
    var b = new UserMessage("same", List.of());
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void nullTextThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> new UserMessage(null, List.of()));
    assertEquals("text must not be null", ex.getMessage());
  }

  @Test
  void nullAttachmentsThrows() {
    assertThrows(NullPointerException.class, () -> new UserMessage("hi", null));
  }

  @Test
  void canonicalConstructorDefaultsNullFileReferencesToEmpty() {
    var message = new UserMessage("hi", List.of(), null);

    assertTrue(message.fileReferences().isEmpty());
  }

  @Test
  void attachmentsListWithNullEntryRejected() {
    var list = new java.util.ArrayList<InlineFile>();
    list.add(null);
    assertThrows(NullPointerException.class, () -> new UserMessage("hi", list));
  }

  @Test
  void blankTextWithoutAttachmentsThrows() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new UserMessage("", List.of()));
    assertEquals(
        "UserMessage must carry non-blank text, an attachment, or a file reference",
        ex.getMessage());
  }

  @Test
  void whitespaceOnlyTextWithoutAttachmentsThrows() {
    assertThrows(IllegalArgumentException.class, () -> new UserMessage("  \t\n", List.of()));
  }

  @Test
  void textFactoryRejectsNull() {
    assertThrows(NullPointerException.class, () -> UserMessage.text(null));
  }

  @Test
  void textFactoryRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> UserMessage.text(" "));
  }

  // ── Builder ───────────────────────────────────────────────────────────────

  @Test
  void builderWithRawBytesAttachment() {
    var msg =
        UserMessage.newBuilder()
            .withText("hello")
            .withAttachment(new byte[] {1, 2, 3}, "image/png")
            .build();
    assertEquals("hello", msg.text());
    assertEquals(1, msg.attachments().size());
    assertEquals("image/png", msg.attachments().get(0).mimeType());
  }

  @Test
  void builderWithInlineFileAttachment() {
    var attachment = InlineFile.of(new byte[] {1}, "application/pdf");
    var msg = UserMessage.newBuilder().withText("see this").withAttachment(attachment).build();
    assertEquals(attachment, msg.attachments().get(0));
  }

  @Test
  void builderRejectsNullAttachment() {
    var b = UserMessage.newBuilder().withText("hi");
    assertThrows(NullPointerException.class, () -> b.withAttachment((InlineFile) null));
  }

  @Test
  void builderRejectsNullText() {
    assertThrows(NullPointerException.class, () -> UserMessage.newBuilder().withText(null));
  }

  @Test
  void builderTextDefaultsToEmpty(@TempDir Path tmp) throws IOException {
    var f = tmp.resolve("img.png");
    Files.write(f, new byte[] {1, 2, 3});
    var msg = UserMessage.newBuilder().withAttachment(f).build();
    assertEquals("", msg.text());
    assertTrue(msg.hasAttachments());
  }

  @Test
  void builderBuildRefusesEmptyAndNoAttachments() {
    var b = UserMessage.newBuilder();
    assertThrows(IllegalArgumentException.class, b::build);
  }

  @Test
  void builderAcceptsFileReferenceWithoutReadingBytes() {
    var reference = FileReference.of("https://example.com/video.mp4", "video/mp4");

    var msg = UserMessage.newBuilder().withFileReference(reference).build();

    assertTrue(msg.hasFileReferences());
    assertEquals(List.of(reference), msg.fileReferences());
    assertTrue(msg.attachments().isEmpty());
  }

  @Test
  void builderRejectsNullFileReference() {
    var builder = UserMessage.newBuilder().withText("video");

    assertThrows(NullPointerException.class, () -> builder.withFileReference(null));
  }

  @Test
  void builderReadsPathAndSniffsMediaType(@TempDir Path tmp) throws IOException {
    var f = tmp.resolve("notes.md");
    Files.writeString(f, "# heading\n", StandardCharsets.UTF_8);
    var msg = UserMessage.newBuilder().withText("look").withAttachment(f).build();
    var att = msg.attachments().get(0);
    assertEquals("text/markdown", att.mimeType());
    assertEquals("# heading\n".length(), att.data().length);
  }

  @Test
  void builderHandlesCsvViaExtension(@TempDir Path tmp) throws IOException {
    var f = tmp.resolve("data.csv");
    Files.writeString(f, "a,b\n1,2\n", StandardCharsets.UTF_8);
    var msg = UserMessage.newBuilder().withText("ingest").withAttachment(f).build();
    assertEquals("text/csv", msg.attachments().get(0).mimeType());
  }

  @Test
  void builderHandlesPngViaExtension(@TempDir Path tmp) throws IOException {
    var f = tmp.resolve("pic.png");
    Files.write(f, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    var msg = UserMessage.newBuilder().withText("?").withAttachment(f).build();
    assertEquals("image/png", msg.attachments().get(0).mimeType());
  }

  @Test
  void builderRejectsUnknownExtension(@TempDir Path tmp) throws IOException {
    var f = tmp.resolve("data.weirdext");
    Files.write(f, new byte[] {1});
    var b = UserMessage.newBuilder().withText("hi");
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withAttachment(f));
    assertTrue(ex.getMessage().contains("could not determine media type"));
  }

  @Test
  void builderRejectsMissingFile(@TempDir Path tmp) {
    var b = UserMessage.newBuilder().withText("hi");
    assertThrows(IOException.class, () -> b.withAttachment(tmp.resolve("missing.png")));
  }

  @Test
  void builderRejectsNullPath() {
    var b = UserMessage.newBuilder();
    assertThrows(NullPointerException.class, () -> b.withAttachment((Path) null));
  }

  @Test
  void newBuilderAndBuildCalleesAreNonNull() {
    assertNotNull(UserMessage.newBuilder());
  }

  @Test
  void detectMediaTypeRecognizesCommonExtensions(@TempDir Path tmp) {
    // Every entry in the curated extension table should detect to SOME non-null MIME. The exact
    // value may vary across OSes (Files.probeContentType uses the system MIME database first), so
    // we don't pin the value here — just that detection succeeds. Pinned-value checks for the
    // formats every OS agrees on live in their own tests above.
    for (var ext :
        List.of(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "bmp",
            "svg",
            "pdf",
            "txt",
            "md",
            "markdown",
            "csv",
            "tsv",
            "json",
            "yaml",
            "yml",
            "xml",
            "html",
            "htm")) {
      var detected = UserMessage.detectMediaType(tmp.resolve("a." + ext));
      assertNotNull(detected, ext);
    }
  }

  @Test
  void detectMediaTypeReturnsNullForUnknownExtension(@TempDir Path tmp) {
    var detected = UserMessage.detectMediaType(tmp.resolve("a.zzqweqweqwe"));
    assertEquals(null, detected);
  }

  @Test
  void detectMediaTypeReturnsNullForBareName(@TempDir Path tmp) {
    var detected = UserMessage.detectMediaType(tmp.resolve("LICENSE"));
    assertEquals(null, detected);
  }
}

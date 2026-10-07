/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session.test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Small, structurally valid binary documents for tests that push a real image or PDF through the
 * Read tool, unit-level or to a live provider's vision and document channels. Every call returns a
 * fresh array.
 */
public final class SampleDocuments {

  /** The text {@link #helloWorldPdf()} renders on its single page. */
  public static final String PDF_TEXT = "Hello, world!";

  /*
   * Anthropic's published vision cookbook bytes. A hand-rolled 67-byte grayscale PNG with valid
   * chunks and CRCs was accepted by Gemini but rejected by Claude with HTTP 400 "Could not process
   * image"; these bytes are accepted by both.
   */
  private static final String PIXEL_PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC";

  private SampleDocuments() {}

  /** A 69-byte 1x1 RGB PNG with valid IHDR, IDAT and IEND chunks and CRCs. */
  public static byte[] pixelPng() {
    return Base64.getDecoder().decode(PIXEL_PNG_BASE64);
  }

  /**
   * A one-page PDF 1.4 rendering {@link #PDF_TEXT} in Helvetica, about 500 bytes, whose xref
   * offsets, stream length and trailer line up byte for byte, so any conforming reader parses it.
   */
  public static byte[] helloWorldPdf() {
    var charset = StandardCharsets.ISO_8859_1;
    // /Length must equal the stream's byte count: a stale /Length 51 against a 46-byte stream once
    // made Gemini accept the file, extract no text and burn maxTurns retrying.
    var streamContent = "BT /F1 24 Tf 100 700 Td (" + PDF_TEXT + ") Tj ET\n";
    var objects =
        new String[] {
          "<< /Type /Catalog /Pages 2 0 R >>",
          "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
          "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
              + "/Resources << /Font << /F1 5 0 R >> >> >>",
          "<< /Length "
              + streamContent.getBytes(charset).length
              + " >>\nstream\n"
              + streamContent
              + "endstream",
          "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        };
    var out = new ByteArrayOutputStream();
    out.writeBytes("%PDF-1.4\n%âãÏÓ\n".getBytes(charset));
    var xref = new StringBuilder("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
    for (var i = 0; i < objects.length; i++) {
      xref.append(String.format("%010d 00000 n %n", out.size()));
      out.writeBytes(((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n").getBytes(charset));
    }
    var xrefStart = out.size();
    out.writeBytes(xref.toString().getBytes(charset));
    out.writeBytes(
        ("trailer\n<< /Size "
                + (objects.length + 1)
                + " /Root 1 0 R >>\nstartxref\n"
                + xrefStart
                + "\n%%EOF\n")
            .getBytes(charset));
    return out.toByteArray();
  }
}

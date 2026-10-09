/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.gemini;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiVideoIntegrationTest {

  /**
   * One second of ffmpeg's {@code testsrc} pattern at 64x64 and 5 fps, H.264 {@code yuv420p}. The
   * clip must be a fully encoded video: the Interactions API answers a malformed one with a bare
   * 500.
   */
  private static final String TINY_MP4 =
      "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAANUbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAA"
          + "A+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAA"
          + "AAAAAAAAAAAAAAAAAAAAAgAAAqN0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAA"
          + "AAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0"
          + "AAAAAAAAAAEAAAPoAAAQAAABAAAAAAIbbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAKABVxAAAAAAALWhk"
          + "bHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABxm1pbmYAAAAUdm1oZAAAAAEAAAAAAAAA"
          + "AAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAYZzdGJsAAAAwnN0c2QAAAAAAAAAAQAAALJh"
          + "dmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABDExhdmMgbGlieDI2NAAAAAAAAAAA"
          + "AAAAAAAAAAAAAAAAGP//AAAAOGF2Y0MBZAAK/+EAGmdkAAqscgREJsBEAAADAAQAAAMAKDxIlhGAAQAHaOhDglLI"
          + "sP34+AAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAmyAAAAAAAAAAYc3R0cwAAAAAAAAABAAAABQAACAAA"
          + "AAAUc3RzcwAAAAAAAAABAAAAAQAAADhjdHRzAAAAAAAAAAUAAAABAAAQAAAAAAEAACgAAAAAAQAAEAAAAAABAAAA"
          + "AAAAAAEAAAgAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAAFAAAAAQAAAChzdHN6AAAAAAAAAAAAAAAFAAAEbgAAADsA"
          + "AAAOAAAAFgAAAAwAAAAUc3RjbwAAAAAAAAABAAADhAAAAD11ZHRhAAAANW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAA"
          + "AG1kaXJhcHBsAAAAAAAAAAAAAAAACGlsc3QAAAAIZnJlZQAABOFtZGF0AAACoQYF//+d3EXpvebZSLeWLNgg2SPu"
          + "73gyNjQgLSBjb3JlIDE2NSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBo"
          + "dHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0xIHJlZj0xNiBkZWJsb2Nr"
          + "PTE6MDowIGFuYWx5c2U9MHgzOjB4MTMzIG1lPXVtaCBzdWJtZT0xMCBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1p"
          + "eGVkX3JlZj0xIG1lX3JhbmdlPTI0IGNocm9tYV9tZT0xIHRyZWxsaXM9MiA4eDhkY3Q9MSBjcW09MCBkZWFkem9u"
          + "ZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTIgbG9va2FoZWFkX3RocmVh"
          + "ZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0w"
          + "IGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz04IGJfcHlyYW1pZD0yIGJfYWRhcHQ9MiBiX2JpYXM9MCBkaXJl"
          + "Y3Q9MyB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTUgc2NlbmVj"
          + "dXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD02MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTM1LjAgcWNv"
          + "bXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAHFZYiB"
          + "ABP/zxGjW//Q9qe01pxHfNDGoNOXliElHIw2cmAOR+SWoUYJ0bqOPHT7WLoBXimqJSc+deEzDuVwjU2/faxUZ02W"
          + "60wohiu/u4MMKBrSsrlhLHb5qeb08p2aPw5QORSzM7RdK18a4bZ+XgqguG3Cm2sNlI7CXWcl8ReR+C7zevph1Xdq"
          + "F7TUVfmhTzVH66ZjnwlT+0jNciOkhjkPqkOjF0tFC1yXaztIKeSzg9dSq5xmRpZ+yeCSC63bg5Az98hWzbwjt1e8"
          + "d4rRYz7XID76o/uECRmmaakAlFL0N5KtwkGKpK283F3OmLyuJ5gxj/ouHf2q7B/GAwBguMvWulwVEGWEr/V96usc"
          + "r5UYXC46n3NegvcsIs53ITOEJ4LI86OSsd06dhQZ2hUU7CnHA8Bj4lDd1dCkrLp/l85gb4ICVPhx7gtF8RYPMAW1"
          + "iTBAhP/L8nxbm4l0h9IYgCtCr3KiITCyS5NgQuIKWB4cpO2RBqblsrWbjqHY5yDS4uxFoAUSwDOi9jZajfKRwB4s"
          + "CyZw+9RvvWlVUAPGezEJti7bOs69sHLbOkhwg/6IP5dB6LWAj8gSOpIoz/KzHNWrcE44f2qBAAAAN0GaCI2Iz36D"
          + "1kJmqhWd9xAAQXGSPR1UDYjBGHllpmJUrkF9FU0MDwsqw/VxZeb3VY0olMyUWkAAAAAKQZ4QRxEfvFwEVQAAABIB"
          + "nhgmiM/DtiaLqQRR1ONKjYAAAAAIAZ4YbUjPi4E=";

  record VideoObservation(String summary) {}

  @Test
  void betaInteractionsAcceptsManagedFileVideoAndParsesStructuredOutput(@TempDir Path tempDir)
      throws Exception {
    var video = tempDir.resolve("tiny.mp4");
    Files.write(video, Base64.getDecoder().decode(TINY_MP4));
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("GEMINI_API_KEY"))
            .withApiVersion("v1beta")
            .withProviderContinuation(false)
            .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
            .build();

    try (var files = new GeminiFilesClient(config);
        var managed = files.uploadManaged(video, "video/mp4");
        var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_7_FLASH.id(), config)) {
      var message =
          Message.newBuilder()
              .withRole(Role.USER)
              .withContent("Describe the visible content in one short sentence.")
              .withFileReferences(List.of(managed.reference()))
              .build();

      var response = model.chat(List.of(message), OutputSchema.of(VideoObservation.class));

      assertNotNull(response.parsed());
    }
  }
}

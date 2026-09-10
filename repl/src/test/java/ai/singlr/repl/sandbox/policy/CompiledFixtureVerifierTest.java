/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Runs javac's real output for the {@link CompiledFixtures} constructs through {@link
 * PolicyBytecodeVerifier} under {@link SandboxPolicy#noEgress()}. Denied constructs must name the
 * API reached through the method reference; allowed constructs must pass untouched.
 */
class CompiledFixtureVerifierTest {

  @Test
  void constructorReferenceToFileReaderIsRejected() {
    var ex = assertDenied(CompiledFixtures.ConstructorReference.class);
    assertEquals("java/io/FileReader", ex.deniedOwner());
    assertEquals("<init>", ex.deniedMember());
    assertEquals("denyFileSystemAccess", ex.rule());
  }

  @Test
  void staticReferenceToClassForNameIsRejected() {
    var ex = assertDenied(CompiledFixtures.StaticReference.class);
    assertEquals("java/lang/Class", ex.deniedOwner());
    assertEquals("forName", ex.deniedMember());
    assertEquals("denyReflection", ex.rule());
  }

  @Test
  void unboundInstanceReferenceToFileListFilesIsRejected() {
    var ex = assertDenied(CompiledFixtures.UnboundInstanceReference.class);
    assertEquals("java/io/File", ex.deniedOwner());
    assertEquals("listFiles", ex.deniedMember());
  }

  @Test
  void boundInstanceReferenceToFileExistsIsRejected() {
    var ex = assertDenied(CompiledFixtures.BoundInstanceReference.class);
    assertEquals("java/io/File", ex.deniedOwner());
    assertEquals("exists", ex.deniedMember());
  }

  @Test
  void methodReferenceInsideNestedLambdaIsRejected() {
    var ex = assertDenied(CompiledFixtures.NestedLambda.class);
    assertEquals("java/io/FileReader", ex.deniedOwner());
    assertEquals("<init>", ex.deniedMember());
  }

  @Test
  void lambdaCapturesAndPrimitiveOrArraySignaturesAreAccepted() {
    assertAccepted(CompiledFixtures.LambdaCaptures.class);
  }

  @Test
  void recordGeneratedMethodsAreAccepted() {
    assertAccepted(CompiledFixtures.RecordShape.Point.class);
    assertAccepted(CompiledFixtures.RecordShape.class);
  }

  @Test
  void stringConcatenationIsAccepted() {
    assertAccepted(CompiledFixtures.Concatenation.class);
  }

  @Test
  void patternSwitchWithQualifiedEnumLabelIsAccepted() {
    assertAccepted(CompiledFixtures.PatternSwitch.class);
  }

  private static SandboxPolicyException assertDenied(Class<?> fixture) {
    return assertThrows(SandboxPolicyException.class, () -> verify(fixture));
  }

  private static void assertAccepted(Class<?> fixture) {
    assertDoesNotThrow(() -> verify(fixture));
  }

  private static void verify(Class<?> fixture) {
    new PolicyBytecodeVerifier(SandboxPolicy.noEgress())
        .verify(fixture.getName().replace('.', '/'), bytesOf(fixture));
  }

  private static byte[] bytesOf(Class<?> fixture) {
    var resource = fixture.getName().substring(fixture.getPackageName().length() + 1) + ".class";
    try (var in = fixture.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("fixture bytes not found: " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

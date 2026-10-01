/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import jdk.jshell.execution.LocalExecutionControl;
import jdk.jshell.spi.ExecutionControl;

/**
 * JShell {@link jdk.jshell.spi.ExecutionControl ExecutionControl} that wraps {@link
 * LocalExecutionControl} and routes every snippet class through a {@link BytecodeVerifier} before
 * the JVM links the bytes.
 *
 * <p>This is the L2 seam: JShell's {@code load(ClassBytecodes[])} entry point is the chokepoint
 * through which every snippet class, every synthetic JShell wrapper, and every {@code var}
 * declaration flows as raw bytes before any classloader sees them. Intercepting here means the
 * verifier sees the post-compile reality of the snippet — string-concatenated class names have
 * resolved to {@code Class} constants, lambda call sites have been desugared into {@code
 * invokedynamic}, and reflection chains are syntactically visible — without us having to chase the
 * source-level surface area.
 *
 * <p>{@link #redefine(ExecutionControl.ClassBytecodes[]) redefine} is intercepted on the same path
 * so a snippet that supersedes a previously-defined class cannot smuggle denied bytecode in via the
 * redefinition route.
 *
 * <p>{@link SandboxPolicyException} thrown by the verifier is rewrapped as {@link
 * ExecutionControl.ClassInstallException} so JShell's class-install bookkeeping stays consistent.
 * In addition, the policy message is written to {@link System#err} before the rewrap fires — JShell
 * swallows the {@code ClassInstallException} silently (snippet stays {@code VALID}, no exception is
 * attached to the {@code SnippetEvent}), so without the stderr write the deployer and the model
 * would have no signal that the snippet was denied. The sandbox bootstrap captures {@code
 * System.err} during {@code execute} and forwards it back to the host as the eval result's {@code
 * stderr}, so the model receives a clean policy traceback through the standard channel.
 */
public final class GuardedExecutionControl extends LocalExecutionControl {

  private final BytecodeVerifier verifier;

  public GuardedExecutionControl(BytecodeVerifier verifier) {
    super();
    if (verifier == null) {
      throw new IllegalArgumentException("verifier must not be null");
    }
    this.verifier = verifier;
  }

  /** Test/utility accessor — exposes the verifier for unit-level introspection. */
  public BytecodeVerifier verifier() {
    return verifier;
  }

  @Override
  public void load(ClassBytecodes[] cbcs)
      throws ClassInstallException, NotImplementedException, EngineTerminationException {
    verifyAll(cbcs);
    super.load(cbcs);
  }

  @Override
  public void redefine(ClassBytecodes[] cbcs)
      throws ClassInstallException, NotImplementedException, EngineTerminationException {
    verifyAll(cbcs);
    super.redefine(cbcs);
  }

  private void verifyAll(ClassBytecodes[] cbcs) throws ClassInstallException {
    if (cbcs == null) {
      return;
    }
    for (var cb : cbcs) {
      try {
        verifier.verify(cb.name(), cb.bytecodes());
      } catch (SandboxPolicyException e) {
        System.err.println(e.getMessage());
        throw new ClassInstallException(e.getMessage(), new boolean[cbcs.length]);
      }
    }
  }
}

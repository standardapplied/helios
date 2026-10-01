/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import java.util.Map;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;

/**
 * Constructs a {@link GuardedExecutionControl} bound to a {@link SandboxPolicy}.
 *
 * <p>Handed directly to {@code JShell.builder().executionEngine(provider, params)} by the
 * subprocess bootstrap — not registered through {@link java.util.ServiceLoader}, because the policy
 * needs to ride along with the provider instance and the SPI-discovery path does not let us inject
 * non-string state. The {@link #name()} value is supplied for completeness; JShell does not use it
 * on the direct-instance path.
 */
public final class GuardedExecutionControlProvider implements ExecutionControlProvider {

  /** SPI name for this provider, returned by {@link #name()}. */
  public static final String NAME = "helios-guarded";

  private final SandboxPolicy policy;
  private final BytecodeVerifier verifier;

  /**
   * Construct a provider bound to {@code policy}. The verifier is built once at construction time
   * via {@link BytecodeVerifier#forPolicy(SandboxPolicy)}; per-snippet calls into {@link
   * GuardedExecutionControl#load} reuse the same instance.
   */
  public GuardedExecutionControlProvider(SandboxPolicy policy) {
    if (policy == null) {
      throw new IllegalArgumentException("policy must not be null");
    }
    this.policy = policy;
    this.verifier = BytecodeVerifier.forPolicy(policy);
  }

  public SandboxPolicy policy() {
    return policy;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Map<String, String> defaultParameters() {
    return Map.of();
  }

  @Override
  public ExecutionControl generate(ExecutionEnv env, Map<String, String> parameters) {
    return new GuardedExecutionControl(verifier);
  }
}

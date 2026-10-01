/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What an {@link ExecutionProvider} declares it can do. Used by the {@code Execute} tool and the
 * surrounding hooks to decide — before dispatch — whether a request is even plausible. A
 * mis-targeted runtime ({@code PYTHON} against a BASH-only provider) is refused without spawning a
 * subprocess.
 *
 * @param supportedRuntimes the set of {@link Runtime} values this provider can dispatch; non-null,
 *     defensively copied as an immutable set
 * @param networkAllowed whether the provider's execution environment can reach outbound networks.
 *     Informational — the provider is still responsible for actually enforcing the boundary
 * @param filesystemWriteAllowed whether scripts can write files inside their execution environment.
 *     Likewise informational; the provider enforces, the field advertises
 * @param maxTimeout the upper bound on any single invocation's wall-clock budget. Requests with a
 *     larger {@link ExecutionRequest#timeout()} are clamped or refused at the provider's
 *     discretion; non-null, strictly positive
 */
public record ExecutionCapabilities(
    Set<Runtime> supportedRuntimes,
    boolean networkAllowed,
    boolean filesystemWriteAllowed,
    Duration maxTimeout) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code supportedRuntimes} or {@code maxTimeout} is null
   * @throws IllegalArgumentException if {@code maxTimeout} is zero or negative
   */
  public ExecutionCapabilities {
    Objects.requireNonNull(supportedRuntimes, "supportedRuntimes must not be null");
    Objects.requireNonNull(maxTimeout, "maxTimeout must not be null");
    if (maxTimeout.isZero() || maxTimeout.isNegative()) {
      throw new IllegalArgumentException("maxTimeout must be strictly positive, got " + maxTimeout);
    }
    supportedRuntimes =
        supportedRuntimes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(supportedRuntimes));
  }

  /**
   * Whether the provider declares support for a given runtime. Convenience that avoids the
   * boilerplate {@code capabilities.supportedRuntimes().contains(rt)} call.
   *
   * @param runtime the runtime to check; non-null
   * @return {@code true} if supported
   * @throws NullPointerException if {@code runtime} is null
   */
  public boolean supports(Runtime runtime) {
    Objects.requireNonNull(runtime, "runtime must not be null");
    return supportedRuntimes.contains(runtime);
  }

  /**
   * Start building an {@code ExecutionCapabilities}.
   *
   * @return a fresh builder with empty supported runtimes, network/filesystem both {@code false},
   *     and a default {@code maxTimeout} of 2 minutes (matches {@code SessionLimits} per-tool
   *     default)
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Mutable builder for {@link ExecutionCapabilities}. */
  public static final class Builder {

    private final Set<Runtime> supportedRuntimes = new HashSet<>();
    private boolean networkAllowed;
    private boolean filesystemWriteAllowed;
    private Duration maxTimeout = Duration.ofMinutes(2);

    private Builder() {}

    /**
     * Replace the supported runtimes with the given set.
     *
     * @param runtimes non-null set
     * @return this builder
     * @throws NullPointerException if {@code runtimes} is null or contains null elements
     */
    public Builder withSupportedRuntimes(Set<Runtime> runtimes) {
      Objects.requireNonNull(runtimes, "runtimes must not be null");
      for (var r : runtimes) {
        Objects.requireNonNull(r, "runtimes must not contain null");
      }
      this.supportedRuntimes.clear();
      this.supportedRuntimes.addAll(runtimes);
      return this;
    }

    /**
     * Add a single runtime to the supported set.
     *
     * @param runtime non-null runtime
     * @return this builder
     * @throws NullPointerException if {@code runtime} is null
     */
    public Builder withSupportedRuntime(Runtime runtime) {
      Objects.requireNonNull(runtime, "runtime must not be null");
      this.supportedRuntimes.add(runtime);
      return this;
    }

    /**
     * Set the network-allowed flag. Defaults to {@code false}.
     *
     * @param networkAllowed whether the execution environment can reach outbound networks
     * @return this builder
     */
    public Builder withNetworkAllowed(boolean networkAllowed) {
      this.networkAllowed = networkAllowed;
      return this;
    }

    /**
     * Set the filesystem-write-allowed flag. Defaults to {@code false}.
     *
     * @param filesystemWriteAllowed whether scripts can write files
     * @return this builder
     */
    public Builder withFilesystemWriteAllowed(boolean filesystemWriteAllowed) {
      this.filesystemWriteAllowed = filesystemWriteAllowed;
      return this;
    }

    /**
     * Set the maximum timeout. Defaults to 2 minutes.
     *
     * @param maxTimeout non-null, strictly positive duration
     * @return this builder
     * @throws NullPointerException if {@code maxTimeout} is null
     * @throws IllegalArgumentException if {@code maxTimeout} is zero or negative
     */
    public Builder withMaxTimeout(Duration maxTimeout) {
      Objects.requireNonNull(maxTimeout, "maxTimeout must not be null");
      if (maxTimeout.isZero() || maxTimeout.isNegative()) {
        throw new IllegalArgumentException(
            "maxTimeout must be strictly positive, got " + maxTimeout);
      }
      this.maxTimeout = maxTimeout;
      return this;
    }

    /**
     * Build the immutable record.
     *
     * @return the capabilities
     */
    public ExecutionCapabilities build() {
      return new ExecutionCapabilities(
          supportedRuntimes, networkAllowed, filesystemWriteAllowed, maxTimeout);
    }
  }
}

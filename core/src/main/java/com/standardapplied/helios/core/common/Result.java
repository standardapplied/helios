/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A result type representing either success or failure. Similar to Rust's Result or Kotlin's
 * Result.
 *
 * @param <T> the type of the success value
 */
public sealed interface Result<T> {

  record Success<T>(T value) implements Result<T> {}

  record Failure<T>(String error, Exception cause) implements Result<T> {
    public Failure(String error) {
      this(error, null);
    }

    @Override
    public String toString() {
      return error;
    }
  }

  default boolean isSuccess() {
    return this instanceof Success;
  }

  default boolean isFailure() {
    return this instanceof Failure;
  }

  default T orElse(T fallback) {
    return switch (this) {
      case Success<T> s -> s.value();
      case Failure<T> f -> fallback;
    };
  }

  default T orElseGet(Function<Failure<T>, T> fallbackFn) {
    return switch (this) {
      case Success<T> s -> s.value();
      case Failure<T> f -> fallbackFn.apply(f);
    };
  }

  default <U> Result<U> map(Function<T, U> fn) {
    return switch (this) {
      case Success<T> s -> {
        try {
          yield new Success<>(fn.apply(s.value()));
        } catch (Exception e) {
          yield new Failure<>(e.getMessage(), e);
        }
      }
      case Failure<T> f -> new Failure<>(f.error(), f.cause());
    };
  }

  default <U> Result<U> flatMap(Function<T, Result<U>> fn) {
    return switch (this) {
      case Success<T> s -> {
        try {
          yield fn.apply(s.value());
        } catch (Exception e) {
          yield new Failure<>(e.getMessage(), e);
        }
      }
      case Failure<T> f -> new Failure<>(f.error(), f.cause());
    };
  }

  default Result<T> recover(Function<Failure<T>, Result<T>> fn) {
    return switch (this) {
      case Success<T> s -> s;
      case Failure<T> f -> fn.apply(f);
    };
  }

  default Result<T> ifSuccess(Consumer<T> action) {
    if (this instanceof Success<T>(T value)) {
      action.accept(value);
    }
    return this;
  }

  default Result<T> ifFailure(Consumer<Failure<T>> action) {
    if (this instanceof Failure<T> f) {
      action.accept(f);
    }
    return this;
  }

  static <T> Result<T> success(T value) {
    return new Success<>(value);
  }

  static <T> Result<T> failure(String error) {
    return new Failure<>(error);
  }

  /** Get the success value or throw a RuntimeException with the failure error and cause. */
  default T getOrThrow() {
    return switch (this) {
      case Success<T>(T value) -> value;
      case Failure<T>(String error, Exception cause) ->
          throw cause != null ? new RuntimeException(error, cause) : new RuntimeException(error);
    };
  }

  static <T> Result<T> failure(String error, Exception cause) {
    return new Failure<>(error, cause);
  }
}

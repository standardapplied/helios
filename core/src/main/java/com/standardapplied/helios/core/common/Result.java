/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A result type representing either success or failure. Similar to Rust's Result or Kotlin's
 * Result.
 *
 * @param <T> the type of the success value
 */
public sealed interface Result<T> {

  /**
   * A successful result carrying its value.
   *
   * @param <T> the type of the success value
   * @param value the success value; may be {@code null}, which the type does not reject
   */
  record Success<T>(T value) implements Result<T> {}

  /**
   * A failed result carrying a description of the failure and, optionally, the exception behind it.
   * Its {@link #toString()} is the {@code error} text alone.
   *
   * @param <T> the type the success value would have had
   * @param error the description of the failure; not validated, so {@code null} is kept as given
   * @param cause the exception that caused the failure; {@code null} when there is none
   */
  record Failure<T>(String error, Exception cause) implements Result<T> {

    /**
     * Creates a failure with no cause.
     *
     * @param error the description of the failure
     */
    public Failure(String error) {
      this(error, null);
    }

    @Override
    public String toString() {
      return error;
    }
  }

  /**
   * Tells whether this result is a {@link Success}.
   *
   * @return {@code true} for a {@link Success}, {@code false} for a {@link Failure}
   */
  default boolean isSuccess() {
    return this instanceof Success;
  }

  /**
   * Tells whether this result is a {@link Failure}.
   *
   * @return {@code true} for a {@link Failure}, {@code false} for a {@link Success}
   */
  default boolean isFailure() {
    return this instanceof Failure;
  }

  /**
   * Returns the success value, or {@code fallback} for a failure.
   *
   * @param fallback the value returned for a failure; may be {@code null}
   * @return the success value, or {@code fallback}
   */
  default T orElse(T fallback) {
    return switch (this) {
      case Success<T> s -> s.value();
      case Failure<T> f -> fallback;
    };
  }

  /**
   * Returns the success value, or the value {@code fallbackFn} computes from the failure. The
   * function is called only for a failure, and whatever it throws propagates.
   *
   * @param fallbackFn computes the value for a failure from it; non-null for a failure
   * @return the success value, or what {@code fallbackFn} returned
   */
  default T orElseGet(Function<Failure<T>, T> fallbackFn) {
    return switch (this) {
      case Success<T> s -> s.value();
      case Failure<T> f -> fallbackFn.apply(f);
    };
  }

  /**
   * Transforms the success value with {@code fn}. A failure is passed on with its error and cause;
   * {@code fn} is not called. An {@link Exception} {@code fn} throws becomes a failure whose error
   * is the exception's message and whose cause is the exception; an {@link Error} propagates.
   *
   * @param <U> the type of the transformed value
   * @param fn maps the success value; non-null for a success
   * @return a success holding what {@code fn} returned, or a failure
   */
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

  /**
   * Chains a computation that itself returns a result. A failure is passed on with its error and
   * cause; {@code fn} is not called. An {@link Exception} {@code fn} throws becomes a failure whose
   * error is the exception's message and whose cause is the exception; an {@link Error} propagates.
   *
   * @param <U> the type of the chained result's value
   * @param fn computes the next result from the success value; non-null for a success
   * @return the result {@code fn} returned, or a failure
   */
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

  /**
   * Replaces a failure with the result {@code fn} computes from it. A success is returned as is and
   * {@code fn} is not called; whatever {@code fn} throws propagates.
   *
   * @param fn computes a replacement result from the failure; non-null for a failure
   * @return this success, or the result {@code fn} returned
   */
  default Result<T> recover(Function<Failure<T>, Result<T>> fn) {
    return switch (this) {
      case Success<T> s -> s;
      case Failure<T> f -> fn.apply(f);
    };
  }

  /**
   * Runs {@code action} with the success value; does nothing for a failure. Whatever {@code action}
   * throws propagates.
   *
   * @param action receives the success value; non-null for a success
   * @return this result, for chaining
   */
  default Result<T> ifSuccess(Consumer<T> action) {
    if (this instanceof Success<T>(T value)) {
      action.accept(value);
    }
    return this;
  }

  /**
   * Runs {@code action} with the failure; does nothing for a success. Whatever {@code action}
   * throws propagates.
   *
   * @param action receives the failure; non-null for a failure
   * @return this result, for chaining
   */
  default Result<T> ifFailure(Consumer<Failure<T>> action) {
    if (this instanceof Failure<T> f) {
      action.accept(f);
    }
    return this;
  }

  /**
   * Creates a {@link Success}.
   *
   * @param <T> the type of the success value
   * @param value the success value; may be {@code null}
   * @return a success holding {@code value}
   */
  static <T> Result<T> success(T value) {
    return new Success<>(value);
  }

  /**
   * Creates a {@link Failure} with no cause.
   *
   * @param <T> the type the success value would have had
   * @param error the description of the failure
   * @return a failure carrying {@code error} and a {@code null} cause
   */
  static <T> Result<T> failure(String error) {
    return new Failure<>(error);
  }

  /**
   * Returns the success value, or throws for a failure.
   *
   * @return the success value
   * @throws RuntimeException for a failure, with the failure's error as its message and the
   *     failure's cause, when it has one, as its cause
   */
  default T getOrThrow() {
    return switch (this) {
      case Success<T>(T value) -> value;
      case Failure<T>(String error, Exception cause) ->
          throw cause != null ? new RuntimeException(error, cause) : new RuntimeException(error);
    };
  }

  /**
   * Creates a {@link Failure} with a cause.
   *
   * @param <T> the type the success value would have had
   * @param error the description of the failure
   * @param cause the exception behind the failure; may be {@code null}
   * @return a failure carrying {@code error} and {@code cause}
   */
  static <T> Result<T> failure(String error, Exception cause) {
    return new Failure<>(error, cause);
  }
}

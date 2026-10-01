/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void successCreation() {
    var result = Result.success("hello");

    assertTrue(result.isSuccess());
    assertFalse(result.isFailure());
    assertEquals("hello", result.orElse("default"));
  }

  @Test
  void failureCreation() {
    var result = Result.<String>failure("error message");

    assertFalse(result.isSuccess());
    assertTrue(result.isFailure());
    assertEquals("default", result.orElse("default"));
  }

  @Test
  void failureWithCause() {
    var cause = new RuntimeException("root cause");
    var result = Result.<String>failure("error", cause);

    assertInstanceOf(Result.Failure.class, result);
    var failure = (Result.Failure<String>) result;
    assertEquals("error", failure.error());
    assertEquals(cause, failure.cause());
  }

  @Test
  void mapSuccess() {
    var result = Result.success(5);
    var mapped = result.map(x -> x * 2);

    assertInstanceOf(Result.Success.class, mapped);
    assertEquals(10, ((Result.Success<Integer>) mapped).value());
  }

  @Test
  void mapFailure() {
    var result = Result.<Integer>failure("error");
    var mapped = result.map(x -> x * 2);

    assertInstanceOf(Result.Failure.class, mapped);
    assertEquals("error", ((Result.Failure<Integer>) mapped).error());
  }

  @Test
  void flatMapSuccess() {
    var result = Result.success(5);
    var flatMapped = result.flatMap(x -> Result.success(x * 2));

    assertInstanceOf(Result.Success.class, flatMapped);
    assertEquals(10, ((Result.Success<Integer>) flatMapped).value());
  }

  @Test
  void flatMapFailure() {
    var result = Result.<Integer>failure("error");
    var flatMapped = result.flatMap(x -> Result.success(x * 2));

    assertInstanceOf(Result.Failure.class, flatMapped);
  }

  @Test
  void orElseGetFailure() {
    var failure = Result.<String>failure("error");
    var value = failure.orElseGet(f -> "recovered from: " + f.error());

    assertEquals("recovered from: error", value);
  }

  @Test
  void orElseGetSuccess() {
    var success = Result.success("original");
    var value = success.orElseGet(f -> "fallback");

    assertEquals("original", value);
  }

  @Test
  void recoverFromFailure() {
    var failure = Result.<String>failure("broken");
    var recovered = failure.recover(f -> Result.success("fixed"));

    assertTrue(recovered.isSuccess());
    assertEquals("fixed", ((Result.Success<String>) recovered).value());
  }

  @Test
  void recoverOnSuccessReturnsOriginal() {
    var success = Result.success("ok");
    var result = success.recover(f -> Result.success("replaced"));

    assertTrue(result.isSuccess());
    assertEquals("ok", ((Result.Success<String>) result).value());
  }

  @Test
  void ifSuccessRunsActionOnSuccess() {
    var values = new java.util.ArrayList<String>();
    var result = Result.success("hello");

    var returned = result.ifSuccess(values::add);

    assertEquals(1, values.size());
    assertEquals("hello", values.getFirst());
    assertEquals(result, returned);
  }

  @Test
  void ifSuccessSkipsActionOnFailure() {
    var values = new java.util.ArrayList<String>();
    var result = Result.<String>failure("broken");

    var returned = result.ifSuccess(values::add);

    assertTrue(values.isEmpty());
    assertEquals(result, returned);
  }

  @Test
  void ifFailureRunsActionOnFailure() {
    var errors = new java.util.ArrayList<String>();
    var result = Result.<String>failure("broken");

    var returned = result.ifFailure(f -> errors.add(f.error()));

    assertEquals(1, errors.size());
    assertEquals("broken", errors.getFirst());
    assertEquals(result, returned);
  }

  @Test
  void ifFailureSkipsActionOnSuccess() {
    var errors = new java.util.ArrayList<String>();
    var result = Result.success("ok");

    var returned = result.ifFailure(f -> errors.add(f.error()));

    assertTrue(errors.isEmpty());
    assertEquals(result, returned);
  }

  @Test
  void mapExceptionWrappedAsFailure() {
    var result = Result.success("hello");
    var mapped =
        result.map(
            v -> {
              throw new RuntimeException("boom");
            });

    assertTrue(mapped.isFailure());
    var failure = (Result.Failure<?>) mapped;
    assertEquals("boom", failure.error());
    assertInstanceOf(RuntimeException.class, failure.cause());
  }

  @Test
  void flatMapExceptionWrappedAsFailure() {
    var result = Result.success("hello");
    var mapped =
        result.flatMap(
            v -> {
              throw new RuntimeException("boom");
            });

    assertTrue(mapped.isFailure());
    var failure = (Result.Failure<?>) mapped;
    assertEquals("boom", failure.error());
    assertInstanceOf(RuntimeException.class, failure.cause());
  }

  @Test
  void getOrThrowOnSuccess() {
    var result = Result.success("hello");

    assertEquals("hello", result.getOrThrow());
  }

  @Test
  void getOrThrowOnFailure() {
    var result = Result.<String>failure("something broke");

    var ex = assertThrows(RuntimeException.class, result::getOrThrow);
    assertEquals("something broke", ex.getMessage());
  }

  @Test
  void getOrThrowOnFailureWithCause() {
    var cause = new IllegalArgumentException("root cause");
    var result = Result.<String>failure("wrapped", cause);

    var ex = assertThrows(RuntimeException.class, result::getOrThrow);
    assertEquals("wrapped", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }

  @Test
  void failureToString() {
    var failure = new Result.Failure<String>("something broke", null);

    assertEquals("something broke", failure.toString());
  }

  @Test
  void patternMatching() {
    var success = Result.success("value");
    var failure = Result.<String>failure("error");

    var successResult =
        switch (success) {
          case Result.Success<String>(var v) -> "got: " + v;
          case Result.Failure<String>(var e, var c) -> "failed: " + e;
        };

    var failureResult =
        switch (failure) {
          case Result.Success<String>(var v) -> "got: " + v;
          case Result.Failure<String>(var e, var c) -> "failed: " + e;
        };

    assertEquals("got: value", successResult);
    assertEquals("failed: error", failureResult);
  }
}

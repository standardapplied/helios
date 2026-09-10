/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.time.DayOfWeek;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * javac-compiled fixtures for {@link CompiledFixtureVerifierTest}. Each nested class isolates one
 * language construct that lowers to {@code INVOKEDYNAMIC} or a dynamic constant, so the test can
 * feed the compiler's real output — not a hand-built classfile — through the verifier.
 */
final class CompiledFixtures {

  private CompiledFixtures() {}

  interface Opener<T> {
    T open(String path) throws IOException;
  }

  interface Loader {
    Class<?> load(String name) throws ClassNotFoundException;
  }

  static final class ConstructorReference {
    static Opener<Reader> opener() {
      return FileReader::new;
    }
  }

  static final class StaticReference {
    static Loader loader() {
      return Class::forName;
    }
  }

  static final class UnboundInstanceReference {
    static Function<File, File[]> lister() {
      return File::listFiles;
    }
  }

  static final class BoundInstanceReference {
    static Supplier<Boolean> exists(File file) {
      return file::exists;
    }
  }

  static final class NestedLambda {
    static Supplier<Opener<Reader>> nested() {
      return () -> FileReader::new;
    }
  }

  static final class LambdaCaptures {
    static IntUnaryOperator scale(int factor) {
      return value -> value * factor;
    }

    static Supplier<int[]> copy(int[] values) {
      return values::clone;
    }

    static IntUnaryOperator abs() {
      return Math::abs;
    }

    static Supplier<Integer> length(String text) {
      return text::length;
    }
  }

  static final class RecordShape {
    record Point(int x, String label) {}

    static String describe(Point point) {
      return point.toString() + point.hashCode() + point.equals(point);
    }
  }

  static final class Concatenation {
    static String join(int count, String label) {
      return "count=" + count + ", label=" + label;
    }
  }

  static final class PatternSwitch {
    static int classify(Object value) {
      return switch (value) {
        case Integer i -> i;
        case String s -> s.length();
        case DayOfWeek.MONDAY -> 1;
        case DayOfWeek d -> d.getValue();
        default -> 0;
      };
    }
  }
}

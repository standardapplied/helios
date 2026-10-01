/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates a JShell pre-eval snippet that exposes each top-level component of an input record as a
 * {@code var} in the sandbox. The model can write {@code numbers.size()} or {@code
 * operation.equals("sum")} directly without parsing the JSON itself.
 *
 * <p><b>Sandbox surface stays narrow.</b> The snippet calls {@link
 * ai.singlr.repl.sandbox.HostBridge#getInput()} to retrieve the input fields as a {@code
 * Map<String, Object>} and casts each field to its declared generic type rendered as Java source.
 * The user's input class never appears in JShell, and no third-party libraries (Jackson, etc.) need
 * to be visible to JShell's compiler. The harness handles JSON conversion host-side; the sandbox
 * sees only {@code java.*} types plus the {@link ai.singlr.repl.sandbox.HostBridge}.
 *
 * <p>Binding tiers:
 *
 * <ul>
 *   <li><b>Fully-{@code java.*} types</b> — {@code String}, {@code Integer}, {@code List<Integer>},
 *       {@code Map<String, Double>}, etc. — bind with full static typing. The model writes {@code
 *       numbers.stream().sum()} directly.
 *   <li><b>Parameterized {@code java.*} containers over user-defined elements</b> — {@code
 *       List<UserType>}, {@code Map<String, UserType>}, {@code Set<UserType>}, nested forms — bind
 *       as the same container with type arguments erased to {@link Object}. The model gets {@code
 *       .size()} / {@code .get()} / iteration without a cast; for element-level access it casts
 *       once to {@code Map<String,Object>} (matching what Jackson's {@code convertValue} produces
 *       at runtime).
 *   <li><b>Non-{@code java.*} classes (top-level or array element)</b> — bind as raw {@link
 *       Object}. The model casts to {@code Map<String,Object>} on first use. This case is rare in
 *       practice; the cast overhead is one line.
 * </ul>
 *
 * <p>This hybrid lets us keep the simple-type ergonomics (the {@code numbers.size()} win from
 * rlm-demo) while not punishing user-typed inputs (the SDTM case that motivated Spec 05).
 *
 * <p>Internal harness-managed variables use a {@code __} prefix to signal "do not depend on this
 * name". Only records are supported as input types; for non-record inputs the harness passes the
 * JSON as the user message and the prompt instructs the model accordingly.
 *
 * <p>Public API: the future CodeAct preset and any other REPL-based composition use this to bind
 * input the same way.
 */
public final class InputBindings {

  private InputBindings() {}

  /** Names of the JShell variables this binding will introduce, in declaration order. */
  public static List<String> boundFieldNames(Class<?> inputType) {
    if (inputType == null || !inputType.isRecord()) {
      return List.of();
    }
    var components = inputType.getRecordComponents();
    if (components.length == 0) {
      return List.of();
    }
    var names = new ArrayList<String>(components.length);
    for (var component : components) {
      names.add(component.getName());
    }
    return Collections.unmodifiableList(names);
  }

  /**
   * Generate the JShell snippet that pre-binds the record components. Returns {@code null} when
   * binding is not applicable (non-record input or empty record), in which case the harness passes
   * the JSON as the user message and the prompt instructs the model to read it from there.
   *
   * @param inputType the user's input record class
   * @return the JShell snippet, or {@code null} if not applicable
   */
  public static String snippet(Class<?> inputType) {
    if (inputType == null || !inputType.isRecord()) {
      return null;
    }
    var components = inputType.getRecordComponents();
    if (components.length == 0) {
      return null;
    }
    var sb = new StringBuilder();
    sb.append("var __input = ai.singlr.repl.sandbox.HostBridge.getInput();\n");
    for (var component : components) {
      var type = component.getGenericType();
      sb.append("var ").append(component.getName()).append(" = ");
      if (isSimpleType(type)) {
        sb.append("(")
            .append(renderTypeAsJavaSource(type))
            .append(") __input.get(\"")
            .append(component.getName())
            .append("\");\n");
      } else {
        sb.append("__input.get(\"").append(component.getName()).append("\");\n");
      }
    }
    return sb.toString();
  }

  /** Components of the input record, or empty if not a record. Used by the prompt builder. */
  public static RecordComponent[] components(Class<?> inputType) {
    if (inputType == null || !inputType.isRecord()) {
      return new RecordComponent[0];
    }
    return inputType.getRecordComponents();
  }

  /**
   * Whether a type is bindable with a typed JShell cast (with type-argument erasure to {@link
   * Object} where needed — see {@link #renderTypeAsJavaSource}).
   *
   * <p>Three cases:
   *
   * <ul>
   *   <li>Primitive or {@code java.*} class → true (typed cast preserves the type).
   *   <li>Array → true iff the component type is bindable.
   *   <li>{@link ParameterizedType} whose raw type is bindable → true. Type arguments may be
   *       non-{@code java.*}; rendering erases them to {@link Object}, which is sound because
   *       Java's generics are type-erased at runtime and Jackson's {@code convertValue} produces
   *       {@code ArrayList}/{@code LinkedHashMap} regardless of the declared element type.
   * </ul>
   *
   * <p>A non-{@code java.*} class (top-level or as an array's component) is NOT bindable — the
   * declared type isn't visible to JShell, so we fall back to raw {@link Object} in {@link
   * #snippet}.
   */
  static boolean isSimpleType(Type type) {
    if (type instanceof Class<?> c) {
      if (c.isPrimitive()) {
        return true;
      }
      if (c.isArray()) {
        return isSimpleType(c.getComponentType());
      }
      return c.getPackageName().startsWith("java.");
    }
    if (type instanceof ParameterizedType pt) {
      return isSimpleType(pt.getRawType());
    }
    return false;
  }

  /**
   * Render a generic type as a Java source-level type expression suitable for use in a JShell cast
   * or {@code TypeReference}.
   *
   * <p>Substitutions applied during rendering:
   *
   * <ul>
   *   <li>Primitives are boxed ({@code int} → {@code java.lang.Integer}) — JShell can't cast {@code
   *       Object} to a primitive.
   *   <li>Non-{@code java.*} classes (and unrecognised type forms) render as {@code
   *       java.lang.Object}. Preserves the surrounding container shape while erasing user types
   *       JShell can't see.
   * </ul>
   *
   * <p>Examples assuming user-defined record {@code Foo}:
   *
   * <pre>
   *   List&lt;Integer&gt;        → java.util.List&lt;java.lang.Integer&gt;
   *   List&lt;Foo&gt;            → java.util.List&lt;java.lang.Object&gt;
   *   Map&lt;String,Foo&gt;       → java.util.Map&lt;java.lang.String, java.lang.Object&gt;
   *   Map&lt;String,List&lt;Foo&gt;&gt; → java.util.Map&lt;java.lang.String, java.util.List&lt;java.lang.Object&gt;&gt;
   *   Foo[]                  → java.lang.Object[]
   * </pre>
   */
  static String renderTypeAsJavaSource(Type type) {
    if (type instanceof Class<?> c) {
      if (c.isPrimitive()) {
        return boxedCanonicalName(c);
      }
      if (c.isArray()) {
        return renderTypeAsJavaSource(c.getComponentType()) + "[]";
      }
      if (c.getPackageName().startsWith("java.")) {
        return c.getCanonicalName();
      }
      return "java.lang.Object";
    }
    if (type instanceof ParameterizedType pt) {
      var raw = ((Class<?>) pt.getRawType()).getCanonicalName();
      var args = new StringBuilder();
      for (var arg : pt.getActualTypeArguments()) {
        if (args.length() > 0) {
          args.append(", ");
        }
        args.append(renderTypeAsJavaSource(arg));
      }
      return raw + "<" + args + ">";
    }
    return "java.lang.Object";
  }

  private static String boxedCanonicalName(Class<?> primitive) {
    if (primitive == int.class) return "java.lang.Integer";
    if (primitive == long.class) return "java.lang.Long";
    if (primitive == double.class) return "java.lang.Double";
    if (primitive == float.class) return "java.lang.Float";
    if (primitive == boolean.class) return "java.lang.Boolean";
    if (primitive == byte.class) return "java.lang.Byte";
    if (primitive == short.class) return "java.lang.Short";
    if (primitive == char.class) return "java.lang.Character";
    throw new IllegalArgumentException("Unknown primitive: " + primitive);
  }
}

/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.schema;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Discovers the properties of a class or interface from its public accessors: {@code getX()},
 * {@code isX()} returning a boolean, and record-style {@code x()}.
 */
final class BeanAccessors {

  private static final Set<String> EXCLUDED_METHODS =
      Set.of(
          "hashCode",
          "toString",
          "getClass",
          "equals",
          "notify",
          "notifyAll",
          "wait",
          "clone",
          "finalize");

  private BeanAccessors() {}

  /** Every public, non-static, no-argument, non-void accessor, sorted by property name. */
  static List<Method> discover(Class<?> clazz) {
    var accessors = new ArrayList<Method>();
    for (var method : clazz.getMethods()) {
      if (isAccessor(method)) {
        accessors.add(method);
      }
    }
    accessors.sort(Comparator.comparing(BeanAccessors::propertyName));
    return accessors;
  }

  private static boolean isAccessor(Method method) {
    return method.getParameterCount() == 0
        && method.getReturnType() != void.class
        && !Modifier.isStatic(method.getModifiers())
        && !method.isSynthetic()
        && !EXCLUDED_METHODS.contains(method.getName());
  }

  static String propertyName(Method method) {
    var name = method.getName();
    if (name.startsWith("get") && name.length() > 3) {
      return Character.toLowerCase(name.charAt(3)) + name.substring(4);
    }
    if (name.startsWith("is") && name.length() > 2 && returnsBoolean(method)) {
      return Character.toLowerCase(name.charAt(2)) + name.substring(3);
    }
    return name;
  }

  private static boolean returnsBoolean(Method method) {
    return method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class;
  }
}

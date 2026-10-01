/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component or method as not required in the generated JSON Schema. Fields without
 * this annotation are required by default.
 *
 * <pre>{@code
 * record Metric(
 *     String label,
 *     String value,
 *     @Nullable String format
 * ) {}
 * // Generates: "required": ["label", "value"]
 * }</pre>
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Nullable {}

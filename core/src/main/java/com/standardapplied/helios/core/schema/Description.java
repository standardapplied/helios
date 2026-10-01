/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a description to a record component, method, or type for JSON Schema generation.
 * Descriptions guide the model's understanding of each field's purpose.
 *
 * <pre>{@code
 * @Description("User profile information")
 * record Profile(
 *     @Description("Full legal name") String name,
 *     @Description("Age in years") int age
 * ) {}
 * }</pre>
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Description {
  String value();
}

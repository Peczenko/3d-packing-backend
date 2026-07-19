package com.packing.backend.api.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the {@link AuthenticatedUser} for the current request into a controller method
 * parameter.
 *
 * <pre>{@code
 * @GetMapping("/me")
 * UserResponse me(@CurrentUser AuthenticatedUser caller) { ... }
 * }</pre>
 *
 * @see CurrentUserArgumentResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}

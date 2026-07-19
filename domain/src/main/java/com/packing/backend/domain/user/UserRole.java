package com.packing.backend.domain.user;

/**
 * Authorisation role. This table is the source of truth; the value is mirrored into a
 * Firebase custom claim so that it also appears in subsequently issued ID tokens.
 */
public enum UserRole {
    USER,
    ADMIN
}

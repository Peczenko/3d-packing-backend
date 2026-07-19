package com.packing.backend.domain.user;

public enum UserStatus {

    ACTIVE,

    /** Suspended by an administrator. The profile is intact and can be re-enabled. */
    DISABLED,

    /**
     * The account was deleted. The row survives as an anonymised tombstone: Firebase ID
     * tokens stay valid for up to an hour after the identity is removed, so without a
     * record of the deletion an already-issued token could call the API and silently
     * re-provision a fresh profile.
     */
    DELETED
}

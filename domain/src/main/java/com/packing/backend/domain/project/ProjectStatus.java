package com.packing.backend.domain.project;

/**
 * {@code DISABLED} is a read-only archive, not a hidden project: members keep listing and
 * downloading, every write is refused until an owner reactivates it.
 */
public enum ProjectStatus {
    ACTIVE,
    DISABLED,
    DELETED
}

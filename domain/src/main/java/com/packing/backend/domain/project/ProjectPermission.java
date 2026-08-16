package com.packing.backend.domain.project;

public enum ProjectPermission {

    // See the project and download its files
    READ,

    // Everything READ allows plus adding, renaming and deleting files
    WRITE,

    // Everything WRITE allows plus renaming, disabling, deleting and membership
    OWNER;

    public boolean allows(ProjectPermission required) {
        return ordinal() >= required.ordinal();
    }
}

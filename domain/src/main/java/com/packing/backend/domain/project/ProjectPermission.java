package com.packing.backend.domain.project;

/**
 * Access level a user holds on one project, independent of the global
 * {@link com.packing.backend.domain.user.UserRole}: an {@code ADMIN} has no implicit reach
 * into a project they are not a member of.
 *
 * <p>Declared weakest first. {@link #allows(ProjectPermission)} is the only place in the
 * system that compares two levels, so widening the model later means changing one method.
 */
public enum ProjectPermission {

    /** See the project and download its files. */
    READ,

    /** Everything {@code READ} allows, plus adding, renaming and deleting files. */
    WRITE,

    /** Everything {@code WRITE} allows, plus renaming, disabling, deleting and membership. */
    OWNER;

    public boolean allows(ProjectPermission required) {
        return ordinal() >= required.ordinal();
    }
}

package com.packing.backend.core.shared;

/**
 * A write was based on a stale read: another transaction changed the aggregate in between.
 *
 * <p>Failing is the point. Without the optimistic lock that raises this, the losing write
 * would silently revert the winner's change — including, for example, restoring a role
 * that an administrator had just changed.
 *
 * <p>Safe for the client to retry after re-reading.
 */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String message) {
        super(message);
    }
}

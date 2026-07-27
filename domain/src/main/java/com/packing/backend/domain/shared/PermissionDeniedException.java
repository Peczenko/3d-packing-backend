package com.packing.backend.domain.shared;

/**
 * The caller may see the resource but not perform this operation on it — mapped to 403.
 *
 * <p>Distinct from {@link ResourceNotFoundException} on purpose. A caller with no access at
 * all gets a 404, so that an id cannot be probed for existence; this exception is for the
 * case where the caller has already proved access and the only missing thing is a higher
 * permission level, which names nothing they did not already know.
 */
public class PermissionDeniedException extends DomainException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}

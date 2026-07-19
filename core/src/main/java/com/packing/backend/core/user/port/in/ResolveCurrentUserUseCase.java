package com.packing.backend.core.user.port.in;

import com.packing.backend.core.user.UserView;

/**
 * Returns the local profile for the authenticated Firebase identity, creating it on first
 * sight (just-in-time provisioning).
 *
 * <p>This is why no other use case has to handle a "user exists in Firebase but not
 * here" case: any request carrying a valid ID token resolves to a profile.
 */
public interface ResolveCurrentUserUseCase {

    UserView resolveCurrentUser(ResolveCurrentUserCommand command);

    /**
     * Claims lifted from a verified Firebase ID token. Plain strings rather than domain
     * value objects: the values come straight off the wire and are validated here, at the
     * boundary.
     *
     * @param displayName may be null — Firebase accounts need not have one
     */
    record ResolveCurrentUserCommand(String firebaseUid, String email, String displayName) {
    }
}

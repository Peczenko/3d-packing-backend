package com.packing.backend.core.user.port.in;

import com.packing.backend.core.user.UserView;
import com.packing.backend.domain.user.UserRole;

import java.util.UUID;

/** Administrative operation: changes a user's role and mirrors it into Firebase. */
public interface AssignUserRoleUseCase {

    UserView assignRole(AssignUserRoleCommand command);

    record AssignUserRoleCommand(UUID userId, UserRole role) {
    }
}

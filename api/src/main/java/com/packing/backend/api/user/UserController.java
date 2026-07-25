package com.packing.backend.api.user;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.user.UserApplicationService;
import com.packing.backend.core.user.UserApplicationService.AssignUserRoleCommand;
import com.packing.backend.core.user.UserApplicationService.ResolveCurrentUserCommand;
import com.packing.backend.core.user.UserApplicationService.UpdateUserProfileCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserApplicationService users;

    /**
     * Returns the caller's profile, creating it on first call. Clients can treat this as
     * "sign in to the backend" immediately after Firebase authentication — there is no
     * separate registration step.
     */
    @GetMapping("/me")
    public UserResponse getCurrentUser(@CurrentUser AuthenticatedUser caller) {
        return UserResponse.from(users.resolveCurrentUser(new ResolveCurrentUserCommand(
                caller.firebaseUid(), caller.email(), caller.displayName())));
    }

    @PatchMapping("/me")
    public UserResponse updateCurrentUser(@CurrentUser AuthenticatedUser caller,
                                          @Valid @RequestBody UpdateUserProfileRequest request) {
        return UserResponse.from(users.updateProfile(new UpdateUserProfileCommand(
                caller.firebaseUid(), request.username(), request.displayName())));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(@CurrentUser AuthenticatedUser caller) {
        users.deleteAccount(caller.firebaseUid());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse assignRole(@PathVariable UUID userId,
                                   @Valid @RequestBody AssignUserRoleRequest request) {
        return UserResponse.from(users.assignRole(
                new AssignUserRoleCommand(userId, request.role())));
    }
}

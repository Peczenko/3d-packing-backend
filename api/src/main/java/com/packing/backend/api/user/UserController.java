package com.packing.backend.api.user;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.user.port.in.AssignUserRoleUseCase;
import com.packing.backend.core.user.port.in.DeleteUserAccountUseCase;
import com.packing.backend.core.user.port.in.ResolveCurrentUserUseCase;
import com.packing.backend.core.user.port.in.UpdateUserProfileUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controllers depend on input ports only — never on an application service class and
 * never on a repository.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final ResolveCurrentUserUseCase resolveCurrentUser;
    private final UpdateUserProfileUseCase updateUserProfile;
    private final DeleteUserAccountUseCase deleteUserAccount;
    private final AssignUserRoleUseCase assignUserRole;

    public UserController(ResolveCurrentUserUseCase resolveCurrentUser,
                          UpdateUserProfileUseCase updateUserProfile,
                          DeleteUserAccountUseCase deleteUserAccount,
                          AssignUserRoleUseCase assignUserRole) {
        this.resolveCurrentUser = resolveCurrentUser;
        this.updateUserProfile = updateUserProfile;
        this.deleteUserAccount = deleteUserAccount;
        this.assignUserRole = assignUserRole;
    }

    /**
     * Returns the caller's profile, creating it on first call. Clients can treat this as
     * "sign in to the backend" immediately after Firebase authentication — there is no
     * separate registration step.
     */
    @GetMapping("/me")
    public UserResponse getCurrentUser(@CurrentUser AuthenticatedUser caller) {
        return UserResponse.from(resolveCurrentUser.resolveCurrentUser(
                new ResolveCurrentUserUseCase.ResolveCurrentUserCommand(
                        caller.firebaseUid(), caller.email(), caller.displayName())));
    }

    @PatchMapping("/me")
    public UserResponse updateCurrentUser(@CurrentUser AuthenticatedUser caller,
                                          @Valid @RequestBody UpdateUserProfileRequest request) {
        return UserResponse.from(updateUserProfile.updateProfile(
                new UpdateUserProfileUseCase.UpdateUserProfileCommand(
                        caller.firebaseUid(), request.username(), request.displayName())));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(@CurrentUser AuthenticatedUser caller) {
        deleteUserAccount.deleteAccount(caller.firebaseUid());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse assignRole(@PathVariable UUID userId,
                                   @Valid @RequestBody AssignUserRoleRequest request) {
        return UserResponse.from(assignUserRole.assignRole(
                new AssignUserRoleUseCase.AssignUserRoleCommand(userId, request.role())));
    }
}

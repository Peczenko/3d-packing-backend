package com.packing.backend.api.user;

import com.packing.backend.api.shared.security.AuthenticatedUser;
import com.packing.backend.api.shared.security.CurrentUser;
import com.packing.backend.core.user.UserApplicationService;
import com.packing.backend.core.user.UserApplicationService.AssignUserRoleCommand;
import com.packing.backend.core.user.UserApplicationService.ResolveCurrentUserCommand;
import com.packing.backend.core.user.UserApplicationService.SearchUsersQuery;
import com.packing.backend.core.user.UserApplicationService.UpdateUserProfileCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "The caller's own profile, plus role administration")
public class UserController {

    private final UserApplicationService users;

    @GetMapping("/me")
    @Operation(operationId = "getCurrentUser",
               summary = "Get the caller's profile, provisioning it on first call")
    @ApiResponse(responseCode = "200", description = "The caller's profile")
    public UserResponse getCurrentUser(@CurrentUser AuthenticatedUser caller) {
        return UserResponse.from(users.resolveCurrentUser(new ResolveCurrentUserCommand(
                                                                                        caller.firebaseUid(),
                                                                                        caller.email(),
                                                                                        caller.displayName())));
    }

    @PatchMapping("/me")
    @Operation(operationId = "updateCurrentUser", summary = "Update the caller's profile")
    @ApiResponse(responseCode = "200", description = "The updated profile")
    public UserResponse updateCurrentUser(@CurrentUser AuthenticatedUser caller,
                                          @Valid @RequestBody UpdateUserProfileRequest request) {
        return UserResponse.from(users.updateProfile(new UpdateUserProfileCommand(
                                                                                  caller.firebaseUid(),
                                                                                  request.username(),
                                                                                  request.displayName())));
    }

    @DeleteMapping("/me")
    @Operation(operationId = "deleteCurrentUser", summary = "Delete the caller's account")
    @ApiResponse(responseCode = "204", description = "Account deleted")
    public ResponseEntity<Void> deleteCurrentUser(@CurrentUser AuthenticatedUser caller) {
        users.deleteAccount(caller.firebaseUid());
        return ResponseEntity.noContent()
                             .build();
    }

    @GetMapping("/search")
    @Operation(operationId = "searchUsers", summary = "Search users for autocomplete")
    @ApiResponse(responseCode = "200", description = "Matching active and disabled users")
    public List<UserSearchResponse> searchUsers(@Valid @ModelAttribute UserSearchRequest request) {
        return users.searchUsers(new SearchUsersQuery(request.pattern(), request.limit()))
                    .stream()
                    .map(UserSearchResponse::from)
                    .toList();
    }

    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(operationId = "assignUserRole", summary = "Assign a role to a user (admin only)")
    @ApiResponse(responseCode = "200", description = "The updated user")
    public UserResponse assignRole(@PathVariable UUID userId,
                                   @Valid @RequestBody AssignUserRoleRequest request) {
        return UserResponse.from(users.assignRole(
                                                  new AssignUserRoleCommand(userId, request.role())));
    }
}

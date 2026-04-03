package com.deliverytracking.controller;

import com.deliverytracking.dto.ApiResponse;
import com.deliverytracking.dto.UserResponse;
import com.deliverytracking.enums.Role;
import com.deliverytracking.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {
        List<UserResponse> users = userService.getAllUsers();
        return ResponseEntity.ok(ApiResponse.success("All users retrieved successfully", users));
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> updateUserRole(
            @PathVariable Long id,
            @RequestParam Role role) {
        UserResponse response = userService.updateUserRole(id, role);
        return ResponseEntity.ok(ApiResponse.success("User role updated to " + role, response));
    }


    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a user")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deleted successfully", null));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HUB_MANAGER')")
    @Operation(summary = "Update user name/email — used when replacing a staff member")
    public ResponseEntity<ApiResponse<UserResponse>> updateUser(
            @PathVariable Long id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone) {
        UserResponse response = userService.updateUser(id, name, email, phone);
        return ResponseEntity.ok(ApiResponse.success("User updated", response));
    }
    
    
    @PutMapping("/{id}/assign-hub/{hubId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'HUB_MANAGER')")
    public ResponseEntity<ApiResponse<UserResponse>> assignToHub(
            @PathVariable Long id,
            @PathVariable Long hubId) {
        UserResponse response = userService.assignUserToHub(id, hubId);
        return ResponseEntity.ok(ApiResponse.success("User assigned to hub", response));
    }
    
    @GetMapping("/hub/{hubId}/staff")
    @PreAuthorize("hasAnyRole('ADMIN', 'HUB_MANAGER')")
    public ResponseEntity<ApiResponse<List<UserResponse>>> getStaffByHub(
            @PathVariable Long hubId) {
        List<UserResponse> staff = userService.getUsersByHub(hubId);
        return ResponseEntity.ok(ApiResponse.success("Hub staff retrieved successfully", staff));
    }
    
    @PutMapping("/hub/{hubId}/deassign-manager")
    public ResponseEntity<?> deassignManager(@PathVariable Long hubId) {
        userService.deassignManager(hubId);
        return ResponseEntity.ok("Manager deassigned successfully");
    }
}

package com.deliverytracking.controller;

import com.deliverytracking.dto.ApiResponse;
import com.deliverytracking.dto.DelayReportRequest;
import com.deliverytracking.dto.DeliveryDateResponse;
import com.deliverytracking.dto.HubStepUpdateRequest;
import com.deliverytracking.dto.OtpConfirmRequest;
import com.deliverytracking.dto.RescheduleRequest;
import com.deliverytracking.dto.ShipmentRouteResponse;
import com.deliverytracking.dto.StatusUpdateRequest;
import com.deliverytracking.dto.StatusUpdateResponse;
import com.deliverytracking.entity.DeliveryLog;
import com.deliverytracking.entity.Shipment;
import com.deliverytracking.entity.ShipmentRoute;
import com.deliverytracking.entity.User;
import com.deliverytracking.exception.ResourceNotFoundException;
import com.deliverytracking.repository.UserRepository;
import com.deliverytracking.service.DeliveryDateService;
import com.deliverytracking.service.DeliveryStatusService;
import com.deliverytracking.service.OtpService;
import com.deliverytracking.service.ShipmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryStatusController {

    private final DeliveryStatusService deliveryStatusService;
    private final UserRepository userRepository;
    private final OtpService otpService;
    private final ShipmentService shipmentService;
    private final DeliveryDateService deliveryDateService;



    @PostMapping("/update-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'HUB_MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<DeliveryLog>> updateStatus(
            @Valid @RequestBody StatusUpdateRequest request) {
        DeliveryLog response = deliveryStatusService.updateStatus(request);
        return ResponseEntity.ok(ApiResponse.success("Delivery status updated successfully", response));
    }
    

    @GetMapping("/timeline/{trackingId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<ApiResponse<List<DeliveryLog>>> getStatusTimeline(
            @PathVariable String trackingId) {
        List<DeliveryLog> timeline = deliveryStatusService.getStatusTimeline(trackingId);
        return ResponseEntity.ok(ApiResponse.success("Status timeline retrieved successfully", timeline));
    }


    @PutMapping("/hub/update-step")
    @PreAuthorize("hasAnyRole('ADMIN','HUB_MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<String>> updateHubStep(
            @Valid @RequestBody HubStepUpdateRequest request,
            Authentication authentication) {

        User staff = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new RuntimeException("Authenticated user not found"));

        deliveryStatusService.updateHubStatus(request, staff);

        return ResponseEntity.ok(ApiResponse.success(
            "Step updated to " + request.getStatus(), null));
    }

    @GetMapping("/hub/my-tasks")
    public ResponseEntity<List<ShipmentRouteResponse>> getMyHubTasks(Authentication authentication) {
        User staff = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new RuntimeException("User not found"));

        List<ShipmentRouteResponse> tasks = deliveryStatusService.getTasksForHub(staff);
        return ResponseEntity.ok(tasks);
    }
    

    @PostMapping("/confirm-otp")
    @PreAuthorize("hasAnyRole('ADMIN', 'HUB_MANAGER','STAFF')")
    public ResponseEntity<ApiResponse<String>> confirmDelivery(
            @Valid @RequestBody OtpConfirmRequest request,
            Authentication authentication) {

        User staff = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new RuntimeException("Authenticated user not found"));

        otpService.confirmDelivery(request, staff);

        return ResponseEntity.ok(ApiResponse.success(
            "Delivery confirmed successfully", null));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<String>> resendOtp(
            @RequestParam String trackingId) {

        otpService.resendOtp(trackingId);

        return ResponseEntity.ok(ApiResponse.success(
            "New OTP sent to customer", null));
    }
    
 
    @GetMapping("/expected-date/{trackingId}")
    public ResponseEntity<ApiResponse<DeliveryDateResponse>> getExpectedDate(
            @PathVariable String trackingId) {

        DeliveryDateResponse response = shipmentService.getDeliveryDate(trackingId);

        return ResponseEntity.ok(ApiResponse.success(
            "Delivery date retrieved", response));
    }


    @PostMapping("/report-delay")
    @PreAuthorize("hasAnyRole('ADMIN','HUB_MANAGER', 'STAFF')")
    @Operation(summary = "Report a delay manually")
    public ResponseEntity<ApiResponse<String>> reportDelay(
            @RequestBody DelayReportRequest request) {

        deliveryDateService.reportDelay(
            request.getTrackingId(),
            request.getReason(),
            request.getAdditionalHours()
        );

        return ResponseEntity.ok(ApiResponse.success(
            "Delay reported and revised date updated", null));
    }

    
    @PutMapping("/reschedule")
    @PreAuthorize("hasAnyRole('STAFF', 'HUB_MANAGER')")
    public ResponseEntity<ApiResponse<String>> rescheduleDelivery(
            @RequestBody RescheduleRequest request) {
     
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User staff = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
     
        deliveryStatusService.rescheduleDelivery(request, staff);
     
        return ResponseEntity.ok(ApiResponse.success(
            "Delivery rescheduled successfully. New OTP sent to customer.", null));
    }
}
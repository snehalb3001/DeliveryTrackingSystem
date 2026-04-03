package com.deliverytracking.service;

import com.deliverytracking.dto.HubStepUpdateRequest;
import com.deliverytracking.dto.RescheduleRequest;
import com.deliverytracking.dto.ShipmentRouteResponse;
import com.deliverytracking.dto.StatusUpdateRequest;
import com.deliverytracking.entity.*;
import com.deliverytracking.enums.ShipmentRouteStatus;
import com.deliverytracking.enums.ShipmentStatus;
import com.deliverytracking.exception.InvalidStatusTransitionException;
import com.deliverytracking.exception.ResourceNotFoundException;
import com.deliverytracking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryStatusService {

    private final DeliveryLogRepository      deliveryLogRepository;     
    private final ShipmentRepository         shipmentRepository;
    private final UserRepository             userRepository;
    private final ShipmentRouteRepository    shipmentRouteRepository;
    private final ShipmentOtpRepository      shipmentOtpRepository;
    private final ShipmentPartiesRepository  shipmentPartiesRepository;
    private final OtpService                 otpService;
    private final DeliveryDateService        deliveryDateService;

    
    @Transactional
    public DeliveryLog updateStatus(StatusUpdateRequest request) {

        Shipment shipment = shipmentRepository.findByTrackingId(request.getTrackingId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + request.getTrackingId()));

        String email = getCurrentUserEmail();
        User updatedBy = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        ShipmentStatus newStatus = request.getStatusAsEnum();

        shipment.setCurrentStatus(newStatus);
        shipmentRepository.save(shipment);

        
        DeliveryLog logEntry = DeliveryLog.builder()
                .shipment(shipment)
                .action("MANUAL_STATUS_UPDATE")
                .status(newStatus)
                .updatedBy(updatedBy)
                .performedBy(updatedBy.getEmail())
                .hub(updatedBy.getHub())
                .location(request.getLocation())
                .remarks(request.getRemarks())
                .build();
        deliveryLogRepository.save(logEntry);

        log.info("Manual status update: shipment {} → {} by {}",
                shipment.getTrackingId(), newStatus, email);

        return logEntry;
    }



    public List<DeliveryLog> getStatusTimeline(String trackingId) {
        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + trackingId));
        return deliveryLogRepository.findAllByShipmentIdOrderByTimestampDesc(shipment.getId());
    }

 

    @Transactional
    public void updateHubStatus(HubStepUpdateRequest request, User staff) {
    	
        validateStepUpdateRequest(request, staff);
       
        ShipmentRoute step = shipmentRouteRepository
                .findByShipmentIdAndStepOrder(request.getShipmentId(), request.getStepOrder())
                .orElseThrow(() -> new ResourceNotFoundException("Route step not found"));

        step.setStatus(request.getStatus().name());
        if (request.getStatus() == ShipmentRouteStatus.ARRIVED) {
            step.setUnlocked(true);
        }
        step.setUpdatedAt(LocalDateTime.now());
        step.setUpdatedByUserId(staff.getId());
        shipmentRouteRepository.save(step);

       
        List<ShipmentRoute> allSteps = shipmentRouteRepository
                .findByShipmentIdOrderByStepOrder(request.getShipmentId());
        boolean isLastStep = (request.getStepOrder() == allSteps.size() - 1);
        Shipment shipment  = step.getShipment();

        
        if (request.getStatus() == ShipmentRouteStatus.DISPATCHED && !isLastStep) {
            shipmentRouteRepository
                    .findByShipmentIdAndStepOrder(
                            request.getShipmentId(), request.getStepOrder() + 1)
                    .ifPresent(next -> {
                        next.setUnlocked(true);
                        next.setStatus(ShipmentRouteStatus.PENDING.name());
                        next.setUpdatedAt(LocalDateTime.now());
                        shipmentRouteRepository.save(next);
                    });
            shipment.setCurrentStatus(ShipmentStatus.IN_TRANSIT);
            shipmentRepository.save(shipment);
            saveLog(shipment, "DISPATCHED", ShipmentStatus.IN_TRANSIT,
                    staff, step.getHub(), step.getHub().getName(), null);
        }

       
        else if (request.getStatus() == ShipmentRouteStatus.ARRIVED && !isLastStep) {
            shipment.setCurrentStatus(ShipmentStatus.IN_TRANSIT);
            shipmentRepository.save(shipment);
            saveLog(shipment, "ARRIVED", ShipmentStatus.IN_TRANSIT,
                    staff, step.getHub(), step.getHub().getName(), null);
        }

        
        else if (isLastStep) {
            handleLastStepTransitions(step, shipment, request, staff);
        }

       
        deliveryDateService.checkAndHandleDelay(shipment, request.getStepOrder());

        log.info("Hub step updated: shipment={} step={} status={} by={}",
                shipment.getTrackingId(), request.getStepOrder(),
                request.getStatus(), staff.getEmail());
    }

    private void handleLastStepTransitions(ShipmentRoute step, Shipment shipment,
                                           HubStepUpdateRequest request, User staff) {

        ShipmentRouteStatus incomingStatus = request.getStatus();

        ShipmentOtp otpRecord = shipmentOtpRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No OTP record found for shipment: " + shipment.getTrackingId()));

        // Case A — Arrived at final hub → OUT_FOR_DELIVERY + send OTP
        if (incomingStatus == ShipmentRouteStatus.ARRIVED) {
            shipment.setCurrentStatus(ShipmentStatus.OUT_FOR_DELIVERY);
            shipmentRepository.save(shipment);
            otpService.generateAndSendOtp(shipment);
            saveLog(shipment, "OUT_FOR_DELIVERY", ShipmentStatus.OUT_FOR_DELIVERY,
                    staff, step.getHub(), step.getHub().getName(),
                    "Package arrived at " + step.getHub().getName() + ". Out for delivery.");
        }

        else if (incomingStatus == ShipmentRouteStatus.DELIVERY_ATTEMPTED) {
            otpRecord.setDeliveryAttempts(otpRecord.getDeliveryAttempts() + 1);
            otpRecord.setLastAttemptAt(LocalDateTime.now());

            if (request.getFailureReason() != null)
                otpRecord.setAttemptFailureReason(request.getFailureReason());

            if (otpRecord.getDeliveryAttempts() >= otpRecord.getMaxDeliveryAttempts()) {
                shipment.setCurrentStatus(ShipmentStatus.RETURNED_TO_SENDER);
                step.setStatus(ShipmentRouteStatus.FAILED.name());
                shipmentRouteRepository.save(step);
                saveLog(shipment, "FAILED", ShipmentStatus.FAILED,
                        staff, step.getHub(), step.getHub().getName(),
                        "Max attempts reached. Returning to sender.");
            } else {
                step.setStatus(ShipmentRouteStatus.ARRIVED.name());
                shipmentRouteRepository.save(step);
                saveLog(shipment, "DELIVERY_ATTEMPTED", ShipmentStatus.DELIVERY_ATTEMPTED,
                        staff, step.getHub(), step.getHub().getName(),
                        "Attempt " + otpRecord.getDeliveryAttempts()
                        + " failed. Reason: " + otpRecord.getAttemptFailureReason());
            }

            shipmentRepository.save(shipment);
            shipmentOtpRepository.save(otpRecord);
        }

        else if (incomingStatus == ShipmentRouteStatus.DELIVERED) {
            shipment.setCurrentStatus(ShipmentStatus.DELIVERED);
            shipmentRepository.save(shipment);
        }
    }

    @Transactional
    public void rescheduleDelivery(RescheduleRequest request, User staff) {

        String trackingId = request.getTrackingId();
        String reason     = request.getReason() != null ? request.getReason() : "CUSTOMER_ABSENT";
        String notes      = request.getNotes();
        String newAddress = request.getNewAddress();
        String newDate    = request.getNewDeliveryDate();

        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + trackingId));

        if (shipment.getCurrentStatus() != ShipmentStatus.DELIVERY_ATTEMPTED &&
            shipment.getCurrentStatus() != ShipmentStatus.OUT_FOR_DELIVERY) {
            throw new RuntimeException(
                    "Cannot reschedule. Current status: " + shipment.getCurrentStatus());
        }

        ShipmentOtp otpRecord = shipmentOtpRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No OTP record for shipment: " + trackingId));

        otpRecord.setDeliveryAttempts(otpRecord.getDeliveryAttempts() + 1);

        if (otpRecord.getDeliveryAttempts() > otpRecord.getMaxDeliveryAttempts())
            throw new RuntimeException("Maximum delivery attempts reached. Cannot reschedule.");

        List<ShipmentRoute> allSteps = shipmentRouteRepository
                .findByShipmentIdOrderByStepOrder(shipment.getId());
        ShipmentRoute lastStep = allSteps.get(allSteps.size() - 1);

        if (staff.getHub() == null ||
            !staff.getHub().getId().equals(lastStep.getHub().getId())) {
            throw new AccessDeniedException(
                    "Only staff at " + lastStep.getHub().getName() + " can reschedule this.");
        }
        if ("ADDRESS_ISSUE".equals(reason) && newAddress != null && !newAddress.isBlank()) {
            ShipmentParties parties = shipmentPartiesRepository
                    .findByShipmentId(shipment.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No parties record for shipment: " + trackingId));
            parties.setReceiverAddress(newAddress);
            shipmentPartiesRepository.save(parties);
        }

        otpRecord.setAttemptFailureReason(
                reason + (notes != null && !notes.isBlank() ? " — " + notes : ""));
        otpRecord.setOtpHash(null);
        otpRecord.setOtpExpiry(null);
        otpRecord.setOtpAttempts(0);
        shipmentOtpRepository.save(otpRecord);

        lastStep.setStatus(ShipmentRouteStatus.ARRIVED.name());
        lastStep.setUpdatedAt(LocalDateTime.now());
        shipmentRouteRepository.save(lastStep);

        shipment.setCurrentStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        shipmentRepository.save(shipment);

        otpService.generateAndSendOtp(shipment);

        String reasonLabel = switch (reason) {
            case "CUSTOMER_ABSENT"  -> "Customer was not available";
            case "ADDRESS_ISSUE"    -> "Address issue (Updated: " + newAddress + ")";
            case "CUSTOMER_REQUEST" -> "Customer requested reschedule";
            default                 -> reason;
        };

        int attemptsRemaining =
                otpRecord.getMaxDeliveryAttempts() - otpRecord.getDeliveryAttempts();

        String timelineMsg = String.format(
                "Rescheduled by %s. Reason: %s. New Date: %s. Attempt %d/%d. %d left.",
                staff.getName(), reasonLabel,
                (newDate != null ? newDate : "Next available slot"),
                otpRecord.getDeliveryAttempts(), otpRecord.getMaxDeliveryAttempts(),
                attemptsRemaining
        );

        saveLog(shipment, "OUT_FOR_DELIVERY", ShipmentStatus.OUT_FOR_DELIVERY,
                staff, lastStep.getHub(), lastStep.getHub().getName(), timelineMsg);
    }

    public List<ShipmentRouteResponse> getTasksForHub(User staff) {
        if (staff.getHub() == null)
            throw new RuntimeException("You are not assigned to any hub.");

        List<ShipmentRoute> routes = shipmentRouteRepository
                .findByHubIdAndIsUnlockedTrueAndStatusIn(
                        staff.getHub().getId(),
                        List.of(ShipmentRouteStatus.PENDING.name(),
                                ShipmentRouteStatus.ARRIVED.name()));

        return routes.stream().map(route -> {
            int totalSteps = shipmentRouteRepository
                    .countByShipmentId(route.getShipment().getId());

            String nextHubName = null;
            String nextHubCity = null;
            if (route.getStepOrder() < totalSteps - 1) {
                nextHubName = shipmentRouteRepository
                        .findByShipmentIdAndStepOrder(
                                route.getShipment().getId(), route.getStepOrder() + 1)
                        .map(next -> next.getHub().getName()).orElse(null);
                nextHubCity = shipmentRouteRepository
                        .findByShipmentIdAndStepOrder(
                                route.getShipment().getId(), route.getStepOrder() + 1)
                        .map(next -> next.getHub().getCity()).orElse(null);
            }

            return ShipmentRouteResponse.builder()
                    .id(route.getId())
                    .shipmentTrackingId(route.getShipment().getTrackingId())
                    .shipmentId(route.getShipment().getId())
                    .hubName(route.getHub().getName())
                    .hubCity(route.getHub().getCity())
                    .stepOrder(route.getStepOrder())
                    .totalSteps(totalSteps)
                    .hubLat(route.getHub().getLatitude())
                    .hubLng(route.getHub().getLongitude())
                    .nextHubName(nextHubName)
                    .nextHubCity(nextHubCity)
                    .isUnlocked(route.isUnlocked())
                    .status(route.getStatus())
                    .updatedAt(route.getUpdatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

   
    private void saveLog(Shipment shipment, String action, ShipmentStatus status,
                         User staff, Hub hub, String location, String customRemarks) {

        String remarks = (customRemarks != null && !customRemarks.isBlank())
                ? customRemarks
                : switch (action) {
                    case "ARRIVED"          -> "Package arrived at " + location;
                    case "DISPATCHED"       -> "Package dispatched from " + location;
                    case "DELIVERED"        -> "Package delivered to customer";
                    case "OUT_FOR_DELIVERY" -> "Package is out for delivery from " + location;
                    default                 -> "Status updated at " + location;
                };

        deliveryLogRepository.save(DeliveryLog.builder()
                .shipment(shipment)
                .action(action)
                .status(status)
                .updatedBy(staff)
                .performedBy(staff.getEmail())
                .hub(hub)
                .location(location)
                .remarks(remarks)
                .build());
    }

    private void validateStepUpdateRequest(HubStepUpdateRequest request, User staff) {

        ShipmentRoute step = shipmentRouteRepository
                .findByShipmentIdAndStepOrder(request.getShipmentId(), request.getStepOrder())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No route step found for shipment "
                        + request.getShipmentId() + " at step " + request.getStepOrder()));

        if (!step.isUnlocked())
            throw new RuntimeException(
                    "This step is locked. Previous hub has not dispatched yet.");

        if (staff.getHub() == null)
            throw new RuntimeException("You are not assigned to any hub. Contact admin.");

        if (!staff.getHub().getId().equals(step.getHub().getId()))
            throw new AccessDeniedException(
                    "You are assigned to " + staff.getHub().getName()
                    + " but this step belongs to " + step.getHub().getName());

        ShipmentRouteStatus currentStatus = ShipmentRouteStatus.valueOf(step.getStatus());
        validateStatusTransition(currentStatus, request.getStatus());
    }
    

    private void validateStatusTransition(ShipmentRouteStatus current,
                                          ShipmentRouteStatus incoming) {
        boolean valid = switch (current) {
            case PENDING -> incoming == ShipmentRouteStatus.ARRIVED;
            case ARRIVED -> incoming == ShipmentRouteStatus.DISPATCHED
                         || incoming == ShipmentRouteStatus.DELIVERED
                         || incoming == ShipmentRouteStatus.DELIVERY_ATTEMPTED
                         || incoming == ShipmentRouteStatus.REJECTED
                         || incoming == ShipmentRouteStatus.FAILED;
            case DELIVERY_ATTEMPTED -> incoming == ShipmentRouteStatus.DELIVERY_ATTEMPTED
                                    || incoming == ShipmentRouteStatus.DELIVERED
                                    || incoming == ShipmentRouteStatus.FAILED;
            default -> false;
        };

        if (!valid)
            throw new InvalidStatusTransitionException(
                    "Invalid status transition: " + current + " → " + incoming);
    }

    private String getCurrentUserEmail() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }
}
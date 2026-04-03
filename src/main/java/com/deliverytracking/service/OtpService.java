package com.deliverytracking.service;

import com.deliverytracking.dto.OtpConfirmRequest;
import com.deliverytracking.entity.*;
import com.deliverytracking.enums.ShipmentRouteStatus;
import com.deliverytracking.enums.ShipmentStatus;
import com.deliverytracking.exception.ResourceNotFoundException;
import com.deliverytracking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private final ShipmentRepository        shipmentRepository;
    private final ShipmentRouteRepository   shipmentRouteRepository;
    private final ShipmentOtpRepository     shipmentOtpRepository;
    private final ShipmentPartiesRepository shipmentPartiesRepository;
    private final DeliveryLogRepository     deliveryLogRepository;   // unified log
    private final PasswordEncoder           passwordEncoder;
    private final EmailService              emailService;

    private static final int OTP_EXPIRY_MINUTES = 15;
    private static final int MAX_OTP_ATTEMPTS   = 3;

   
    @Transactional
    public void generateAndSendOtp(Shipment shipment) {

        ShipmentOtp otpEntity = shipmentOtpRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OTP record missing for shipment: " + shipment.getTrackingId()));

        ShipmentParties parties = shipmentPartiesRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Parties record missing for shipment: " + shipment.getTrackingId()));

        String otp = String.format("%06d", new SecureRandom().nextInt(999999));
        log.info("===== OTP FOR {} : {} =====", shipment.getTrackingId(), otp);

      
        otpEntity.setOtpHash(passwordEncoder.encode(otp));
        otpEntity.setOtpExpiry(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES));
        otpEntity.setOtpVerified(false);
        otpEntity.setOtpAttempts(0);
        shipmentOtpRepository.save(otpEntity);

     
        emailService.sendDeliveryOtp(
                parties.getReceiverPhone(),
                parties.getReceiverEmail(),
                otp,
                shipment.getTrackingId()
        );
    }

   
    @Transactional
    public void confirmDelivery(OtpConfirmRequest request, User staff) {

        Shipment shipment = shipmentRepository.findByTrackingId(request.getTrackingId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + request.getTrackingId()));

        ShipmentOtp otpEntity = shipmentOtpRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OTP record missing for shipment: " + request.getTrackingId()));

        
        if (otpEntity.isOtpVerified())
            throw new RuntimeException("This shipment has already been delivered.");

        if (otpEntity.getOtpHash() == null)
            throw new RuntimeException(
                    "OTP has not been generated yet. Last hub must trigger OUT_FOR_DELIVERY first.");

        if (otpEntity.getOtpAttempts() >= MAX_OTP_ATTEMPTS)
            throw new RuntimeException(
                    "Maximum OTP attempts exceeded. Please request a new OTP via /resend-otp.");

        if (LocalDateTime.now().isAfter(otpEntity.getOtpExpiry()))
            throw new RuntimeException(
                    "OTP has expired. Please request a new OTP via /resend-otp.");

        
        if (!passwordEncoder.matches(request.getOtp(), otpEntity.getOtpHash())) {
            otpEntity.setOtpAttempts(otpEntity.getOtpAttempts() + 1);
            shipmentOtpRepository.save(otpEntity);
            int remaining = MAX_OTP_ATTEMPTS - otpEntity.getOtpAttempts();
            throw new RuntimeException("Invalid OTP. " + remaining + " attempt(s) remaining.");
        }

     
        otpEntity.setOtpVerified(true);
        otpEntity.setOtpHash(null);
        otpEntity.setOtpExpiry(null);
        otpEntity.setOtpAttempts(0);
        otpEntity.setDeliveredAt(LocalDateTime.now());
        otpEntity.setDeliveredByStaffId(staff.getId());
        shipmentOtpRepository.save(otpEntity);

        shipment.setCurrentStatus(ShipmentStatus.DELIVERED);
        shipmentRepository.save(shipment);

        
        deliveryLogRepository.save(DeliveryLog.builder()
                .shipment(shipment)
                .action("DELIVERED")
                .status(ShipmentStatus.DELIVERED)
                .updatedBy(staff)
                .performedBy(staff.getEmail())
                .hub(staff.getHub())
                .location("Delivered to customer")
                .remarks("OTP verified. Delivered by " + staff.getName())
                .build());

        
        List<ShipmentRoute> allSteps = shipmentRouteRepository
                .findByShipmentIdOrderByStepOrder(shipment.getId());
        if (!allSteps.isEmpty()) {
            ShipmentRoute lastStep = allSteps.get(allSteps.size() - 1);
            lastStep.setStatus(ShipmentRouteStatus.DELIVERED.name());
            lastStep.setUpdatedAt(LocalDateTime.now());
            lastStep.setUpdatedByUserId(staff.getId());
            shipmentRouteRepository.save(lastStep);
        }

       
        emailService.sendDeliveredConfirmationEmail(shipment);

        log.info("Shipment {} delivered by staff {} at {}",
                shipment.getTrackingId(), staff.getEmail(), otpEntity.getDeliveredAt());
    }

    @Transactional
    public void resendOtp(String trackingId) {

        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + trackingId));

        ShipmentOtp otpEntity = shipmentOtpRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "OTP record missing for shipment: " + trackingId));

        if (otpEntity.isOtpVerified())
            throw new RuntimeException("Shipment already delivered. Cannot resend OTP.");

        if (shipment.getCurrentStatus() != ShipmentStatus.OUT_FOR_DELIVERY)
            throw new RuntimeException(
                    "Shipment is not out for delivery yet. Current status: "
                    + shipment.getCurrentStatus());

        generateAndSendOtp(shipment);
        log.info("OTP resent for shipment {}", trackingId);
    }
}
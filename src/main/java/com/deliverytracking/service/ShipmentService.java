package com.deliverytracking.service;

import com.deliverytracking.dto.DeliveryDateResponse;
import com.deliverytracking.dto.ShipmentRequest;
import com.deliverytracking.dto.ShipmentResponse;
import com.deliverytracking.entity.*;
import com.deliverytracking.enums.ShipmentRouteStatus;
import com.deliverytracking.enums.ShipmentStatus;
import com.deliverytracking.exception.ResourceNotFoundException;
import com.deliverytracking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository              shipmentRepository;
    private final ShipmentPartiesRepository       shipmentPartiesRepository;
    private final ShipmentGeoDetailsRepository    shipmentGeoDetailsRepository;
    private final ShipmentOtpRepository           shipmentOtpRepository;
    private final ShipmentDeliveryDatesRepository shipmentDeliveryDatesRepository;
    private final UserRepository                  userRepository;
    private final DeliveryLogRepository           deliveryLogRepository;   // unified log
    private final GeoRoutingService               geoRoutingService;
    private final ShipmentRouteRepository         shipmentRouteRepository;
    private final DeliveryDateService             deliveryDateService;
    private final EmailService                    emailService;

    
    @Transactional
    public ShipmentResponse createShipment(ShipmentRequest request) {
        String email = getCurrentUserEmail();
        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        
        double[] originCoords = geoRoutingService.geocodeAddress(request.getOrigin());
        double[] destCoords   = geoRoutingService.geocodeAddress(request.getDestination());

        if (originCoords == null)
            throw new IllegalArgumentException("Could not geocode origin: " + request.getOrigin());
        if (destCoords == null)
            throw new IllegalArgumentException("Could not geocode destination: " + request.getDestination());

        
        Shipment shipment = Shipment.builder()
                .trackingId(generateUniqueTrackingId())
                .weight(request.getWeight())
                .description(request.getDescription())
                .currentStatus(ShipmentStatus.CREATED)
                .createdBy(currentUser)
                .build();
        Shipment saved = shipmentRepository.save(shipment);

       
        ShipmentParties parties = ShipmentParties.builder()
                .shipment(saved)
                .senderName(request.getSenderName())
                .senderPhone(request.getSenderPhone())
                .senderEmail(request.getSenderEmail())
                .senderAddress(request.getSenderAddress())
                .receiverName(request.getReceiverName())
                .receiverPhone(request.getReceiverPhone())
                .receiverEmail(request.getReceiverEmail())
                .receiverAddress(request.getReceiverAddress())
                .build();
        shipmentPartiesRepository.save(parties);

        
        ShipmentGeoDetails geoDetails = ShipmentGeoDetails.builder()
                .shipment(saved)
                .origin(request.getOrigin())
                .destination(request.getDestination())
                .originLat(originCoords[0])
                .originLng(originCoords[1])
                .destinationLat(destCoords[0])
                .destinationLng(destCoords[1])
                .build();
        shipmentGeoDetailsRepository.save(geoDetails);

        
        Hub originHub      = geoRoutingService.findNearestHub(originCoords[0], originCoords[1]);
        Hub destinationHub = geoRoutingService.findNearestHub(destCoords[0], destCoords[1]);

        if (originHub == null)
            throw new IllegalArgumentException("No hub found near origin: " + request.getOrigin());
        if (destinationHub == null)
            throw new IllegalArgumentException("No hub found near destination: " + request.getDestination());

        List<Hub> route = geoRoutingService.buildHubRoute(originHub, destinationHub);

        for (int i = 0; i < route.size(); i++) {
            ShipmentRoute step = ShipmentRoute.builder()
                    .shipment(saved)
                    .hub(route.get(i))
                    .stepOrder(i)
                    .isUnlocked(i == 0)
                    .status(i == 0
                            ? ShipmentRouteStatus.PENDING.name()
                            : ShipmentRouteStatus.LOCKED.name())
                    .build();
            shipmentRouteRepository.save(step);
        }

        
        LocalDateTime expectedDate = deliveryDateService.calculateExpectedDeliveryDate(
                originCoords[0], originCoords[1],
                destCoords[0],   destCoords[1],
                route.size()
        );
        ShipmentDeliveryDates deliveryDates = ShipmentDeliveryDates.builder()
                .shipment(saved)
                .expectedDeliveryDate(expectedDate)
                .isDelayed(false)
                .build();
        shipmentDeliveryDatesRepository.save(deliveryDates);

       
        ShipmentOtp otp = ShipmentOtp.builder()
                .shipment(saved)
                .otpVerified(false)
                .otpAttempts(0)
                .deliveryAttempts(0)
                .maxDeliveryAttempts(3)
                .build();
        shipmentOtpRepository.save(otp);

       
        deliveryLogRepository.save(DeliveryLog.builder()
                .shipment(saved)
                .action("SHIPMENT_CREATED")
                .status(ShipmentStatus.CREATED)
                .performedBy(currentUser.getEmail())
                .updatedBy(currentUser)
                .location(request.getOrigin())
                .remarks("Shipment registered. Package to be picked up from "
                        + request.getSenderName()
                        + ". Route: " + route.size() + " hubs. Expected: " + expectedDate)
                .build());

       
        saved.setCurrentStatus(ShipmentStatus.DISPATCHED);
        Shipment dispatched = shipmentRepository.save(saved);

      
        deliveryLogRepository.save(DeliveryLog.builder()
                .shipment(dispatched)
                .action("DISPATCHED")
                .status(ShipmentStatus.DISPATCHED)
                .performedBy(currentUser.getEmail())
                .updatedBy(currentUser)
                .location(request.getSenderAddress())
                .remarks("Package picked up from sender. Heading to " + route.get(0).getName())
                .build());

        log.info("Shipment {} created and dispatched to first hub.", dispatched.getTrackingId());

        
        emailService.sendShipmentCreatedEmail(dispatched);
        emailService.sendShipmentReceiverEmail(dispatched);

        return mapToResponse(dispatched);
    }

   
    public List<ShipmentResponse> getAllShipments() {
        return shipmentRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ShipmentResponse getShipmentByTrackingId(String trackingId) {
        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + trackingId));
        return mapToResponse(shipment);
    }

    public List<ShipmentResponse> getShipmentsByStatus(ShipmentStatus status) {
        return shipmentRepository.findAllByCurrentStatus(status)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<DeliveryLog> getStatusTimeline(String trackingId) {
        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + trackingId));
        return deliveryLogRepository.findAllByShipmentIdOrderByTimestampDesc(shipment.getId());
    }


    @Transactional
    public void deleteShipment(Long id) {
        Shipment shipment = shipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found: " + id));

      
        deliveryLogRepository.deleteAll(
                deliveryLogRepository.findAllByShipmentOrderByTimestampDesc(shipment));

   
        shipmentRouteRepository.deleteAll(
                shipmentRouteRepository.findByShipmentIdOrderByStepOrder(shipment.getId()));

       
        shipmentOtpRepository.findByShipmentId(shipment.getId())
                .ifPresent(shipmentOtpRepository::delete);

     
        shipmentGeoDetailsRepository.findByShipmentId(shipment.getId())
                .ifPresent(shipmentGeoDetailsRepository::delete);

      
        shipmentPartiesRepository.findByShipmentId(shipment.getId())
                .ifPresent(shipmentPartiesRepository::delete);

      
        shipmentDeliveryDatesRepository.findByShipmentId(shipment.getId())
                .ifPresent(shipmentDeliveryDatesRepository::delete);

        shipmentRepository.delete(shipment);
        log.info("Shipment {} and all related records deleted.", shipment.getTrackingId());
    }

   
    public DeliveryDateResponse getDeliveryDate(String trackingId) {
        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shipment not found: " + trackingId));

        ShipmentDeliveryDates dates = shipmentDeliveryDatesRepository
                .findByShipmentId(shipment.getId()).orElse(null);

        LocalDateTime referenceDate = (dates != null && dates.getRevisedDeliveryDate() != null)
                ? dates.getRevisedDeliveryDate()
                : (dates != null ? dates.getExpectedDeliveryDate() : null);

        String message;
        if (shipment.getCurrentStatus() == ShipmentStatus.DELIVERED) {
            message = "Delivered";
        } else if (referenceDate == null) {
            message = "Delivery date not yet calculated";
        } else {
            long daysLeft = java.time.Duration.between(
                    LocalDateTime.now(), referenceDate).toDays();
            boolean delayed = dates.isDelayed();
            if (daysLeft <= 0)
                message = delayed ? "Delayed — arriving today"      : "Expected today";
            else if (daysLeft == 1)
                message = delayed ? "Delayed — expected tomorrow"   : "Expected tomorrow";
            else
                message = delayed
                        ? "Delayed — expected in " + daysLeft + " days"
                        : "Expected in "           + daysLeft + " days";
        }

        return DeliveryDateResponse.builder()
                .trackingId(trackingId)
                .expectedDeliveryDate(dates != null ? dates.getExpectedDeliveryDate() : null)
                .revisedDeliveryDate(dates  != null ? dates.getRevisedDeliveryDate()  : null)
                .isDelayed(dates != null && dates.isDelayed())
                .delayReason(dates != null ? dates.getDelayReason() : null)
                .estimatedDaysMessage(message)
                .build();
    }

    
    ShipmentResponse mapToResponse(Shipment shipment) {

        ShipmentParties       parties = shipmentPartiesRepository
                .findByShipmentId(shipment.getId()).orElse(null);
        ShipmentGeoDetails    geo     = shipmentGeoDetailsRepository
                .findByShipmentId(shipment.getId()).orElse(null);
        ShipmentDeliveryDates dates   = shipmentDeliveryDatesRepository
                .findByShipmentId(shipment.getId()).orElse(null);

        LocalDateTime referenceDate = (dates != null && dates.getRevisedDeliveryDate() != null)
                ? dates.getRevisedDeliveryDate()
                : (dates != null ? dates.getExpectedDeliveryDate() : null);

        String message = "Calculating...";
        if (referenceDate != null) {
            long daysLeft = java.time.Duration.between(
                    LocalDateTime.now(), referenceDate).toDays();
            boolean delayed = dates.isDelayed();

            if (shipment.getCurrentStatus() == ShipmentStatus.DELIVERED) {
                message = "Delivered";
            } else if (daysLeft <= 0) {
                message = delayed ? "Delayed — arriving today"    : "Expected today";
            } else if (daysLeft == 1) {
                message = delayed ? "Delayed — expected tomorrow" : "Expected tomorrow";
            } else {
                message = delayed
                        ? "Delayed — expected in " + daysLeft + " days"
                        : "Expected in "           + daysLeft + " days";
            }
        }

        return ShipmentResponse.builder()
                .id(shipment.getId())
                .trackingId(shipment.getTrackingId())
                .senderName(parties      != null ? parties.getSenderName()       : null)
                .senderAddress(parties   != null ? parties.getSenderAddress()    : null)
                .receiverName(parties    != null ? parties.getReceiverName()     : null)
                .receiverAddress(parties != null ? parties.getReceiverAddress()  : null)
                .receiverPhone(parties   != null ? parties.getReceiverPhone()    : null)
                .receiverEmail(parties   != null ? parties.getReceiverEmail()    : null)
                .origin(geo              != null ? geo.getOrigin()               : null)
                .destination(geo         != null ? geo.getDestination()          : null)
                .weight(shipment.getWeight())
                .description(shipment.getDescription())
                .currentStatus(shipment.getCurrentStatus())
                .createdByEmail(shipment.getCreatedBy().getEmail())
                .expectedDeliveryDate(dates != null ? dates.getExpectedDeliveryDate() : null)
                .revisedDeliveryDate(dates  != null ? dates.getRevisedDeliveryDate()  : null)
                .isDelayed(dates != null && dates.isDelayed())
                .delayReason(dates != null ? dates.getDelayReason()                   : null)
                .estimatedDaysMessage(message)
                .createdAt(shipment.getCreatedAt())
                .updatedAt(shipment.getUpdatedAt())
                .build();
    }

   
    private String generateUniqueTrackingId() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        String trackingId;
        do {
            StringBuilder sb = new StringBuilder("TRK-");
            for (int i = 0; i < 8; i++)
                sb.append(chars.charAt(random.nextInt(chars.length())));
            trackingId = sb.toString();
        } while (shipmentRepository.existsByTrackingId(trackingId));
        return trackingId;
    }

    private String getCurrentUserEmail() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        return authentication.getName();
    }
}
package com.deliverytracking.service;

import com.deliverytracking.dto.StatusUpdateResponse;
import com.deliverytracking.dto.TrackingResponse;
import com.deliverytracking.entity.DeliveryLog;
import com.deliverytracking.entity.Shipment;
import com.deliverytracking.exception.ResourceNotFoundException;
import com.deliverytracking.repository.DeliveryLogRepository;
import com.deliverytracking.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrackingService {

    private final ShipmentRepository    shipmentRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final ShipmentService       shipmentService;

    public TrackingResponse publicTrack(String trackingId) {

        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No shipment found with tracking ID: " + trackingId));

        List<StatusUpdateResponse> timeline = deliveryLogRepository
                .findStatusChangesByShipmentId(shipment.getId())
                .stream()
                .map(log -> StatusUpdateResponse.builder()
                        .id(log.getId())
                        .trackingId(shipment.getTrackingId())
                        .status(log.getStatus())
                        .remarks(log.getRemarks())
                        .location(log.getLocation())
                        .updatedByEmail(log.getUpdatedBy() != null
                                ? log.getUpdatedBy().getEmail()
                                : "System (Automated)")
                        .updatedAt(log.getTimestamp())
                        .build())
                .collect(Collectors.toList());

        return TrackingResponse.builder()
                .shipmentDetails(shipmentService.mapToResponse(shipment))
                .statusTimeline(timeline)
                .build();
    }
}
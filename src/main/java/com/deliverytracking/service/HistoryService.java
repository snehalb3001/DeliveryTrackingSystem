package com.deliverytracking.service;

import com.deliverytracking.dto.HistoryResponse;
import com.deliverytracking.entity.DeliveryLog;
import com.deliverytracking.entity.Shipment;
import com.deliverytracking.exception.ResourceNotFoundException;
import com.deliverytracking.repository.DeliveryLogRepository;
import com.deliverytracking.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HistoryService {

    private final DeliveryLogRepository deliveryLogRepository;
    private final ShipmentRepository    shipmentRepository;

    public List<HistoryResponse> getAllHistory() {
        return deliveryLogRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<HistoryResponse> getHistoryByShipment(String trackingId) {
        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found: " + trackingId));
        return deliveryLogRepository.findAllByShipmentOrderByTimestampDesc(shipment)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<HistoryResponse> getHubActivityLog(Long hubId) {
        return deliveryLogRepository.findAllByHubId(hubId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<HistoryResponse> getHistoryByHubAndDateRange(Long hubId,
                                                              LocalDateTime from,
                                                              LocalDateTime to) {
        return deliveryLogRepository.findAllByHubIdAndDateRange(hubId, from, to)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<HistoryResponse> getHistoryByDateRange(LocalDateTime from, LocalDateTime to) {
        return deliveryLogRepository.findAllByTimestampBetweenOrderByTimestampDesc(from, to)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ─── Single mapper — DeliveryLog has hub directly, no route JOIN needed ───

    private HistoryResponse mapToResponse(DeliveryLog log) {
        return HistoryResponse.builder()
                .id(log.getId())
                .trackingId(log.getShipment() != null ? log.getShipment().getTrackingId() : "N/A")
                .action(log.getAction() != null ? log.getAction()
                        : (log.getStatus() != null ? log.getStatus().toString() : "UNKNOWN"))
                .performedBy(log.getUpdatedBy() != null
                        ? log.getUpdatedBy().getName()
                        : (log.getPerformedBy() != null ? log.getPerformedBy() : "System"))
                .timestamp(log.getTimestamp())
                .details(log.getRemarks())
                .hubId(log.getHub()   != null ? log.getHub().getId()   : null)
                .hubName(log.getHub() != null ? log.getHub().getName() : log.getLocation())
                .build();
    }
}
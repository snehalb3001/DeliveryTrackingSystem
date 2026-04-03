package com.deliverytracking.controller;

import com.deliverytracking.dto.ApiResponse;
import com.deliverytracking.dto.HistoryResponse;
import com.deliverytracking.service.HistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<HistoryResponse>>> getAllHistory() {
        List<HistoryResponse> history = historyService.getAllHistory();
        return ResponseEntity.ok(ApiResponse.success("All history retrieved successfully", history));
    }

    @GetMapping("/shipment/{trackingId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<HistoryResponse>>> getHistoryByShipment(@PathVariable String trackingId) {
        List<HistoryResponse> history = historyService.getHistoryByShipment(trackingId);
        return ResponseEntity.ok(ApiResponse.success("History for shipment " + trackingId + " retrieved", history));
    }

    @GetMapping("/date-range")
    @PreAuthorize("hasAnyRole('ADMIN','HUB_MANAGER')")
    public ResponseEntity<ApiResponse<List<HistoryResponse>>> getHistoryByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        List<HistoryResponse> history = historyService.getHistoryByDateRange(from, to);
        return ResponseEntity.ok(ApiResponse.success("History retrieved for date range", history));
    }
    
    
    @GetMapping("/hub/{hubId}")
    @PreAuthorize("hasAnyRole('ADMIN','HUB_MANAGER')")
    @Operation(summary = "Get history by hub", description = "Returns all history for shipments passing through the given hub.")
    public ResponseEntity<ApiResponse<List<HistoryResponse>>> getHistoryByHub(@PathVariable Long hubId) {
        return ResponseEntity.ok(
            ApiResponse.success("Hub history retrieved", historyService.getHubActivityLog(hubId))
        );
    }

    @GetMapping("/hub/{hubId}/date-range")
    @PreAuthorize("hasAnyRole('ADMIN','HUB_MANAGER')")
    public ResponseEntity<ApiResponse<List<HistoryResponse>>> getHistoryByHubAndDateRange(
            @PathVariable Long hubId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(
            ApiResponse.success("Hub history retrieved for date range",
                historyService.getHistoryByHubAndDateRange(hubId, from, to))
        );
    }
}

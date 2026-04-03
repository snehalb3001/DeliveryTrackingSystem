package com.deliverytracking.controller;

import com.deliverytracking.dto.ApiResponse;
import com.deliverytracking.dto.TrackingResponse;
import com.deliverytracking.service.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/track")
@RequiredArgsConstructor
public class TrackingController {

    private final TrackingService trackingService;

    @GetMapping("/{trackingId}")
    public ResponseEntity<ApiResponse<TrackingResponse>> trackShipment(@PathVariable String trackingId) {
        TrackingResponse response = trackingService.publicTrack(trackingId);
        return ResponseEntity.ok(ApiResponse.success("Shipment tracking details retrieved", response));
    }
}

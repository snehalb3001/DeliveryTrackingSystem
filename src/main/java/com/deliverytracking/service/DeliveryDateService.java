package com.deliverytracking.service;

import com.deliverytracking.entity.DeliveryLog;
import com.deliverytracking.entity.Shipment;
import com.deliverytracking.entity.ShipmentDeliveryDates;
import com.deliverytracking.entity.ShipmentRoute;
import com.deliverytracking.enums.ShipmentStatus;
import com.deliverytracking.repository.DeliveryLogRepository;
import com.deliverytracking.repository.ShipmentDeliveryDatesRepository;
import com.deliverytracking.repository.ShipmentRepository;
import com.deliverytracking.repository.ShipmentRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryDateService {

    private final ShipmentRepository              shipmentRepository;
    private final ShipmentRouteRepository         shipmentRouteRepository;
    private final ShipmentDeliveryDatesRepository deliveryDatesRepository;
    private final DeliveryLogRepository           deliveryLogRepository;  // unified log

    private static final int    DELAY_THRESHOLD_HOURS = 6;
    private static final int    EARLY_THRESHOLD_HOURS = 2;
    private static final int    DELIVERY_START_HOUR   = 9;
    private static final int    DELIVERY_END_HOUR     = 19;
    private static final double AVG_SPEED_KMPH        = 60.0;
    private static final double HOURS_PER_HUB         = 12.0;
    private static final double MIN_HOURS             = 6.0;
    private static final double LOCAL_THRESHOLD_KM    = 50.0;
    private static final double SHORT_THRESHOLD_KM    = 300.0;
    private static final double MEDIUM_THRESHOLD_KM   = 800.0;

 

    public LocalDateTime calculateExpectedDeliveryDate(
            double originLat, double originLng,
            double destLat,   double destLng,
            int totalHubs) {

        double distanceKm   = haversineDistance(originLat, originLng, destLat, destLng);
        double transitHours = calculateTransitHours(distanceKm);
        double hubOverhead  = (totalHubs > 1) ? (totalHubs - 1) * HOURS_PER_HUB : 0;
        double totalHours   = transitHours + hubOverhead;

        totalHours = Math.ceil(totalHours * 1.2);   // 20% buffer
        totalHours = Math.max(totalHours, MIN_HOURS); // minimum 6 hours

        return snapToDeliveryWindow(LocalDateTime.now().plusHours((long) totalHours));
    }

   

    @Transactional
    public void checkAndHandleDelay(Shipment shipment, int currentStepOrder) {

        List<ShipmentRoute> allSteps = shipmentRouteRepository
                .findByShipmentIdOrderByStepOrder(shipment.getId());

        if (allSteps.isEmpty()) return;

        int totalSteps     = allSteps.size();
        int remainingSteps = totalSteps - currentStepOrder - 1;
        LocalDateTime now  = LocalDateTime.now();

        ShipmentDeliveryDates dates = deliveryDatesRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new RuntimeException(
                        "No delivery dates record for shipment: " + shipment.getTrackingId()));

        LocalDateTime expectedDate = dates.getExpectedDeliveryDate();
        if (expectedDate == null) {
            log.warn("Shipment {} has no expectedDeliveryDate — skipping delay check.",
                    shipment.getTrackingId());
            return;
        }

        long totalExpectedHours = java.time.Duration.between(
                shipment.getCreatedAt(), expectedDate).toHours();
        if (totalExpectedHours <= 0) return;

        long expectedHoursAtStep = (totalExpectedHours * currentStepOrder) / totalSteps;
        long hoursElapsed        = java.time.Duration.between(
                shipment.getCreatedAt(), now).toHours();
        long diff                = hoursElapsed - expectedHoursAtStep;

        log.info("Delay check for {}: step={}/{}, elapsed={}h, expectedAtStep={}h, diff={}h",
                shipment.getTrackingId(), currentStepOrder, totalSteps,
                hoursElapsed, expectedHoursAtStep, diff);

        if (diff > DELAY_THRESHOLD_HOURS) {

            long hoursPerStep   = totalExpectedHours / totalSteps;
            long remainingHours = (long) Math.ceil(remainingSteps * hoursPerStep * 1.2);
            LocalDateTime revised = snapToDeliveryWindow(
                    now.plusHours(Math.max(remainingHours, 6)));

            if (!dates.isDelayed()) {
                dates.setDelayed(true);
                dates.setDelayReason("Running " + diff + " hours behind schedule.");
                dates.setRevisedDeliveryDate(revised);
                deliveryDatesRepository.save(dates);

                saveSystemLog(shipment, ShipmentStatus.DELAYED,
                        "Shipment is running " + diff + " hours behind schedule. "
                        + "Revised delivery: " + revised + ".");
                log.warn("Auto-delay flagged for {} — {} hours overdue.",
                        shipment.getTrackingId(), diff);
            } else {
                dates.setRevisedDeliveryDate(revised);
                deliveryDatesRepository.save(dates);
                log.info("Revised date updated for already-delayed shipment {}.",
                        shipment.getTrackingId());
            }

        } else if (diff < -EARLY_THRESHOLD_HOURS && dates.isDelayed()) {

            long hoursPerStep   = totalExpectedHours / totalSteps;
            long remainingHours = remainingSteps * hoursPerStep;
            LocalDateTime revised = snapToDeliveryWindow(
                    now.plusHours(Math.max(remainingHours, 6)));

            dates.setDelayed(false);
            dates.setDelayReason(null);
            dates.setRevisedDeliveryDate(revised);
            deliveryDatesRepository.save(dates);

            saveSystemLog(shipment, ShipmentStatus.IN_TRANSIT,
                    "Shipment recovered and is ahead of schedule. Revised delivery: " + revised + ".");
            log.info("Shipment {} recovered from delay.", shipment.getTrackingId());

        } else if (Math.abs(diff) <= DELAY_THRESHOLD_HOURS && dates.isDelayed()) {

            dates.setDelayed(false);
            dates.setDelayReason(null);
            deliveryDatesRepository.save(dates);

            saveSystemLog(shipment, ShipmentStatus.IN_TRANSIT, "Shipment is back on track.");
            log.info("Shipment {} back on track.", shipment.getTrackingId());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // MANUAL DELAY REPORT
    // ─────────────────────────────────────────────────────────────────

    @Transactional
    public void reportDelay(String trackingId, String reason, int additionalHours) {

        Shipment shipment = shipmentRepository.findByTrackingId(trackingId)
                .orElseThrow(() -> new RuntimeException("Shipment not found: " + trackingId));

        if (shipment.getCurrentStatus() == ShipmentStatus.DELIVERED)
            throw new RuntimeException("Cannot report delay on a delivered shipment.");
        if (shipment.getCurrentStatus() == ShipmentStatus.OUT_FOR_DELIVERY)
            throw new RuntimeException(
                    "Shipment is already out for delivery. Cannot update delivery date.");

        ShipmentDeliveryDates dates = deliveryDatesRepository
                .findByShipmentId(shipment.getId())
                .orElseThrow(() -> new RuntimeException(
                        "No delivery dates record for shipment: " + trackingId));

        LocalDateTime currentExpected = dates.getRevisedDeliveryDate() != null
                ? dates.getRevisedDeliveryDate()
                : dates.getExpectedDeliveryDate();

        LocalDateTime newRevisedDate = currentExpected.plusHours(additionalHours);

        dates.setDelayed(true);
        dates.setRevisedDeliveryDate(newRevisedDate);
        dates.setDelayReason(reason);
        deliveryDatesRepository.save(dates);

        saveSystemLog(shipment, ShipmentStatus.DELAYED,
                "Delivery delayed by " + additionalHours + " hours. "
                + "Reason: " + reason + ". "
                + "Revised delivery date: " + newRevisedDate + ".");

        log.info("Manual delay reported for {} — reason: {}, +{}h, new date: {}",
                trackingId, reason, additionalHours, newRevisedDate);
    }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    private double calculateTransitHours(double distanceKm) {
        if (distanceKm <= LOCAL_THRESHOLD_KM)       return 6.0;
        else if (distanceKm <= SHORT_THRESHOLD_KM)  return (distanceKm / AVG_SPEED_KMPH) + 12.0;
        else if (distanceKm <= MEDIUM_THRESHOLD_KM) return (distanceKm / AVG_SPEED_KMPH) + 24.0;
        else                                        return (distanceKm / AVG_SPEED_KMPH) + 48.0;
    }

    private LocalDateTime snapToDeliveryWindow(LocalDateTime rawDate) {
        int hour = rawDate.getHour();
        if (hour < DELIVERY_START_HOUR)
            return rawDate.withHour(DELIVERY_START_HOUR).withMinute(0).withSecond(0);
        else if (hour >= DELIVERY_END_HOUR)
            return rawDate.plusDays(1).withHour(DELIVERY_START_HOUR).withMinute(0).withSecond(0);
        return rawDate.withSecond(0);
    }

    public double haversineDistance(double lat1, double lng1,
                                    double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // System-generated log entry — updatedBy is null (shown as "System (Automated)")
    private void saveSystemLog(Shipment shipment, ShipmentStatus status, String remarks) {
        deliveryLogRepository.save(DeliveryLog.builder()
                .shipment(shipment)
                .action("SYSTEM_AUTO")
                .status(status)
                .performedBy("System (Automated)")
                .location("System")
                .remarks(remarks)
                .build());
    }
}
package com.deliverytracking.repository;

import com.deliverytracking.entity.DeliveryLog;
import com.deliverytracking.entity.Shipment;
import com.deliverytracking.enums.ShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {

    // replaces: findAllByShipmentOrderByTimestampDesc + findAllByShipmentOrderByUpdatedAtDesc
    List<DeliveryLog> findAllByShipmentOrderByTimestampDesc(Shipment shipment);

    // replaces: findByShipmentIdOrderByUpdatedAtDesc
    List<DeliveryLog> findAllByShipmentIdOrderByTimestampDesc(Long shipmentId);

    // replaces: findAllByTimestampBetweenOrderByTimestampDesc
    List<DeliveryLog> findAllByTimestampBetweenOrderByTimestampDesc(LocalDateTime from, LocalDateTime to);

    // replaces: findAllByHubId + findByHubId
    @Query("SELECT l FROM DeliveryLog l WHERE l.hub.id = :hubId ORDER BY l.timestamp DESC")
    List<DeliveryLog> findAllByHubId(@Param("hubId") Long hubId);

    // replaces: findAllByHubIdAndDateRange + findByHubIdAndDateRange
    @Query("""
        SELECT l FROM DeliveryLog l
        WHERE l.hub.id = :hubId
        AND l.timestamp BETWEEN :from AND :to
        ORDER BY l.timestamp DESC
    """)
    List<DeliveryLog> findAllByHubIdAndDateRange(
        @Param("hubId") Long hubId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );

    // bonus: filter by action type when you only want status-change events
    @Query("SELECT l FROM DeliveryLog l WHERE l.shipment.id = :shipmentId AND l.status IS NOT NULL ORDER BY l.timestamp DESC")
    List<DeliveryLog> findStatusChangesByShipmentId(@Param("shipmentId") Long shipmentId);

    // bonus: filter by specific status across all shipments (useful for admin dashboards)
    List<DeliveryLog> findAllByStatusOrderByTimestampDesc(ShipmentStatus status);
}
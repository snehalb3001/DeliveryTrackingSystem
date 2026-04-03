package com.deliverytracking.repository;

import com.deliverytracking.entity.ShipmentDeliveryDates;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShipmentDeliveryDatesRepository extends JpaRepository<ShipmentDeliveryDates, Long> {
    Optional<ShipmentDeliveryDates> findByShipmentId(Long shipmentId);
}
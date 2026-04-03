package com.deliverytracking.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.deliverytracking.entity.ShipmentGeoDetails;

public interface ShipmentGeoDetailsRepository extends JpaRepository<ShipmentGeoDetails, Long> {
    Optional<ShipmentGeoDetails> findByShipmentId(Long shipmentId);
}

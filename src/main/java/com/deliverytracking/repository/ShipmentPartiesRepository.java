package com.deliverytracking.repository;

import com.deliverytracking.entity.ShipmentParties;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShipmentPartiesRepository extends JpaRepository<ShipmentParties, Long> {

    Optional<ShipmentParties> findByShipmentId(Long shipmentId);
    
    
}
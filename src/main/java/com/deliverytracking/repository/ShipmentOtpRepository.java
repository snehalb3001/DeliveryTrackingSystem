
package com.deliverytracking.repository;

import com.deliverytracking.entity.ShipmentOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ShipmentOtpRepository extends JpaRepository<ShipmentOtp, Long> {
    Optional<ShipmentOtp> findByShipmentId(Long shipmentId);
}


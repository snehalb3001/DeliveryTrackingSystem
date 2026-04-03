package com.deliverytracking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.deliverytracking.entity.Shipment;
import com.deliverytracking.entity.ShipmentRoute;

public interface ShipmentRouteRepository extends JpaRepository<ShipmentRoute, Long> {
	
    List<ShipmentRoute> findByShipmentIdOrderByStepOrder(Long shipmentId);
    
    Optional<ShipmentRoute> findByShipmentIdAndStepOrder(Long shipmentId, int step);
    
	List<ShipmentRoute> findByHubIdAndIsUnlockedTrueAndStatusIn(Long hubId, List<String> of);
	
	List<ShipmentRoute> findByShipment(Shipment shipment);
	
    List<ShipmentRoute> findByShipmentOrderByStepOrderAsc(Shipment shipment);
    
    int countByShipmentId(Long shipmentId);
}
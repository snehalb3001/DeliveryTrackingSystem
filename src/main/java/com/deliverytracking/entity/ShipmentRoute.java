package com.deliverytracking.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Table(name = "shipment_routes")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipmentRoute {
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Shipment shipment;

    @ManyToOne
    private Hub hub;

    private int stepOrder;          
    private boolean isUnlocked;     
    private String status;         
    private LocalDateTime updatedAt;
    private Long updatedByUserId;
}

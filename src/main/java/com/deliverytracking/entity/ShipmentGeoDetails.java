package com.deliverytracking.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "shipment_geo_details")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipmentGeoDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false, unique = true)
    private Shipment shipment;

    @Column(nullable = false)
    private String origin;              

    @Column(nullable = false)
    private String destination;         

    @Column(nullable = false)
    private double originLat;           
    
    @Column(nullable = false)
    private double originLng;

    @Column(nullable = false)
    private double destinationLat;

    @Column(nullable = false)
    private double destinationLng;
}
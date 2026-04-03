package com.deliverytracking.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.deliverytracking.enums.ShipmentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "delivery_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(nullable = false)
    private String action;         

    @Enumerated(EnumType.STRING)
    private ShipmentStatus status;

    private String performedBy; 

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_user_id")
    private User updatedBy;    

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hub_id")
    private Hub hub;

    private String location;
    @Column(columnDefinition = "TEXT")
    private String remarks;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
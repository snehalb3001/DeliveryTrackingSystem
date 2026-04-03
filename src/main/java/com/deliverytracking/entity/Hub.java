package com.deliverytracking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "hubs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder

public class Hub {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String city;

    private double latitude;
    private double longitude;

    @Column(nullable = false)
    private boolean active = true;     
    
    @OneToMany(mappedBy = "hub", fetch = FetchType.LAZY)
    @JsonIgnore   
    private List<User> staff;
    
    @OneToOne 
    @JoinColumn(name = "manager_id")
    @JsonIgnore
    private User manager;


}

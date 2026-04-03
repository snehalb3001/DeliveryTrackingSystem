package com.deliverytracking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HubRequest {

    @NotBlank(message = "Hub name is required")
    private String name;

    @NotBlank(message = "City is required")
    private String city;

    private double latitude;   
    private double longitude;   
}
package com.rumeshchathuranga.springapi.dtos;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class CheckoutRequest {
    @NotNull(message = "Cart Id is required")
    private UUID cartId;

}

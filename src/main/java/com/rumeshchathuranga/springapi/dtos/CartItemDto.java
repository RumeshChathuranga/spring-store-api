package com.rumeshchathuranga.springapi.dtos;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemDto {
    private cartProductDto product;
    private Integer quantity;
    private BigDecimal totalPrice;
}

package com.rumeshchathuranga.springapi.carts;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemDto {
    private cartProductDto product;
    private Integer quantity;
    private BigDecimal totalPrice;
}

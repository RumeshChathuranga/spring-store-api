package com.rumeshchathuranga.springapi.carts;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class cartProductDto {
    private Long id;
    private  String name;
    private BigDecimal price;
}

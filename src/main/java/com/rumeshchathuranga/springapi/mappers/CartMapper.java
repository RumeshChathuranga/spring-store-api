package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.CartDto;
import com.rumeshchathuranga.springapi.dtos.CartItemDto;
import com.rumeshchathuranga.springapi.entities.Cart;
import com.rumeshchathuranga.springapi.entities.CartItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CartMapper {
    @Mapping(target = "items", source = "items")
    @Mapping(target = "totalPrice", expression = "java(cart.getTotalPrice())")
    CartDto toDto(Cart cart);

    @Mapping(target = "totalPrice",expression = "java(cartItem.getTotalPrice())")
    CartItemDto toDto(CartItem cartItem);
}

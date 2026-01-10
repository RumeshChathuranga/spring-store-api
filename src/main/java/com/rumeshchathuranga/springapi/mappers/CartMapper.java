package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.CartDto;
import com.rumeshchathuranga.springapi.entities.Cart;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CartMapper {
    CartDto toDto(Cart cart);
}

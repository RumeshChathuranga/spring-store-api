package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.OrderDto;
import com.rumeshchathuranga.springapi.entities.Order;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderDto toDto(Order order);
}

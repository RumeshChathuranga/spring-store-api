package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.ProductDto;
import com.rumeshchathuranga.springapi.entities.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mapping(target = "categoryId", source = "category.id")
    ProductDto toDto(Product product);
}

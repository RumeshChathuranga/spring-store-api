package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.UserDto;
import com.rumeshchathuranga.springapi.entities.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
    UserDto toDto(User user);
}

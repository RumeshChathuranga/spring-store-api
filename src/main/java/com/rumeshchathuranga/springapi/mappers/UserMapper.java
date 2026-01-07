package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.UserDto;
import com.rumeshchathuranga.springapi.entities.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {
    UserDto toDto(User user);
}

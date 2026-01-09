package com.rumeshchathuranga.springapi.mappers;

import com.rumeshchathuranga.springapi.dtos.RegisterUserRequest;
import com.rumeshchathuranga.springapi.dtos.UpdateUserRequest;
import com.rumeshchathuranga.springapi.dtos.UserDto;
import com.rumeshchathuranga.springapi.entities.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
    UserDto toDto(User user);
    User toEntity(RegisterUserRequest request);
    void updateUser(UpdateUserRequest request, @MappingTarget User user);
}

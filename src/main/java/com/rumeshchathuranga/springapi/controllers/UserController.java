package com.rumeshchathuranga.springapi.controllers;

import com.rumeshchathuranga.springapi.dtos.UserDto;
import com.rumeshchathuranga.springapi.entities.User;
import com.rumeshchathuranga.springapi.mappers.UserMapper;
import com.rumeshchathuranga.springapi.repositories.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;


@RestController
@AllArgsConstructor
@RequestMapping("/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @GetMapping
    public Iterable<UserDto> getUsers(
           @RequestParam(required = false, defaultValue = "", name = "sort") String sort
           //In future if we change the parameter name to SortBy like the code dosent break
    ) {
        if(!Set.of("name", "email").contains(sort))
            sort="name";
        return userRepository.findAll(Sort.by(sort).ascending())
                .stream()
                .map(userMapper::toDto)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable Long id){
        var user = userRepository.findById(id).orElse(null);
        if(user == null){
//            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(userMapper.toDto(user));
    }
}

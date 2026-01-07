package com.rumeshchathuranga.springapi.controllers;

import com.rumeshchathuranga.springapi.entities.User;
import com.rumeshchathuranga.springapi.repositories.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@AllArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/users")
    public Iterable<User> getUsers() {
        return userRepository.findAll();
    }
}

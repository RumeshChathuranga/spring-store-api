package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.User;
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User, Long> {
  }
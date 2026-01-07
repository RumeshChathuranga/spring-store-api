package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends JpaRepository<User, Long> {
  }
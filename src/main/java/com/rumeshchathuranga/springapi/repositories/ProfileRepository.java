package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.Profile;
import org.springframework.data.repository.CrudRepository;

public interface ProfileRepository extends CrudRepository<Profile, Long> {
  }
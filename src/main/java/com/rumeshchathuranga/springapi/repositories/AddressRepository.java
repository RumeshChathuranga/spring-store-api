package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.Address;
import org.springframework.data.repository.CrudRepository;

public interface AddressRepository extends CrudRepository<Address, Long> {
  }
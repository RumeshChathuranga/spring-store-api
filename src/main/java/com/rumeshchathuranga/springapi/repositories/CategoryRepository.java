package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.Category;
import org.springframework.data.repository.CrudRepository;

public interface CategoryRepository extends CrudRepository<Category, Byte> {
  }
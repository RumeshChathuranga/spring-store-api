package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
  }
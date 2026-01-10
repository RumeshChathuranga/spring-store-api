package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.Cart;
import org.springframework.data.jpa.repository.support.JpaRepositoryImplementation;

import java.util.UUID;

public interface CartRepository extends JpaRepositoryImplementation<Cart, UUID> {
}
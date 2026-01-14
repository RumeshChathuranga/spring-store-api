package com.rumeshchathuranga.springapi.repositories;

import com.rumeshchathuranga.springapi.entities.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
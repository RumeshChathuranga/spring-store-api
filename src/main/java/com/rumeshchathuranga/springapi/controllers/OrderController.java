package com.rumeshchathuranga.springapi.controllers;

import com.rumeshchathuranga.springapi.dtos.OrderDto;
import com.rumeshchathuranga.springapi.services.OrderService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@AllArgsConstructor
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orderService;

    @GetMapping
    public List<OrderDto> getAllOrders(){
        return orderService.getAllOrders();
    }
}

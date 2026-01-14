package com.rumeshchathuranga.springapi.services;

import com.rumeshchathuranga.springapi.dtos.OrderDto;
import com.rumeshchathuranga.springapi.mappers.OrderMapper;
import com.rumeshchathuranga.springapi.repositories.OrderRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class OrderService {
    private final AuthService authService;
    private OrderRepository orderRepository;
    private OrderMapper orderMapper;

    public List<OrderDto> getAllOrders(){
        var user = authService.getCurrentUser();
        var orders = orderRepository.getAllByCustomer(user);
        return orders.stream().map(orderMapper::toDto).toList();
    }
}

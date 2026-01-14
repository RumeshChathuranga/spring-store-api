package com.rumeshchathuranga.springapi.services;

import com.rumeshchathuranga.springapi.dtos.OrderDto;
import com.rumeshchathuranga.springapi.exceptions.OrderNotFoundException;
import com.rumeshchathuranga.springapi.mappers.OrderMapper;
import com.rumeshchathuranga.springapi.repositories.OrderRepository;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
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
        var orders = orderRepository.getOrdersByCustomer(user);
        return orders.stream().map(orderMapper::toDto).toList();
    }

    public OrderDto getOrder(Long orderId) {
        var order = orderRepository
                .getOrderWithItems(orderId)
                .orElseThrow(OrderNotFoundException::new);

        var user = authService.getCurrentUser();
        if(!order.isPlacedBy(user)) {
            throw new AccessDeniedException("Access denied");
        }
        return orderMapper.toDto(order);
    }
}


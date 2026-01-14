package com.rumeshchathuranga.springapi.controllers;

import com.rumeshchathuranga.springapi.dtos.CheckoutRequest;
import com.rumeshchathuranga.springapi.dtos.CheckoutResponse;
import com.rumeshchathuranga.springapi.dtos.ErrorDto;
import com.rumeshchathuranga.springapi.entities.Order;
import com.rumeshchathuranga.springapi.entities.OrderItem;
import com.rumeshchathuranga.springapi.entities.OrderStatus;
import com.rumeshchathuranga.springapi.repositories.CartRepository;
import com.rumeshchathuranga.springapi.repositories.OrderRepository;
import com.rumeshchathuranga.springapi.services.AuthService;
import com.rumeshchathuranga.springapi.services.CartService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@AllArgsConstructor
@RequestMapping("/checkout")
public class CheckoutController {
    private final CartRepository cartRepository;
    private final AuthService authService;
    private final OrderRepository orderRepository;
    private final CartService cartService;

    @PostMapping
    public ResponseEntity<?> checkout(
            @Valid @RequestBody CheckoutRequest request
    ) {
        var cart = cartRepository.getCartsWithItems(request.getCartId()).orElse(null);
        if (cart == null) {
            return ResponseEntity.badRequest().body(new ErrorDto("Cart not found"));
        }

        if(cart.getItems().isEmpty()){
            return ResponseEntity.badRequest().body(new ErrorDto("Cart is empty"));
        }

        var order = Order.fromCart(cart, authService.getCurrentUser());

        orderRepository.save(order);

        cartService.clearCart(cart.getId());

        return ResponseEntity.ok(new CheckoutResponse(order.getId()));

    }
}

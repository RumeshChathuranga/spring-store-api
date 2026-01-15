package com.rumeshchathuranga.springapi.services;

import com.rumeshchathuranga.springapi.dtos.CheckoutRequest;
import com.rumeshchathuranga.springapi.dtos.CheckoutResponse;
import com.rumeshchathuranga.springapi.entities.Order;
import com.rumeshchathuranga.springapi.exceptions.CartEmptyException;
import com.rumeshchathuranga.springapi.exceptions.CartNotFoundException;
import com.rumeshchathuranga.springapi.exceptions.PaymentException;
import com.rumeshchathuranga.springapi.repositories.CartRepository;
import com.rumeshchathuranga.springapi.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckoutService {
    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final AuthService authService;
    private final CartService cartService;
    private final PaymentGateway paymentGateway;


    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request){
        var cart = cartRepository.getCartsWithItems(request.getCartId()).orElse(null);
        if (cart == null) {
            throw  new CartNotFoundException();
        }

        if(cart.isEmpty()){
            throw new CartEmptyException();
        }

        var order = Order.fromCart(cart, authService.getCurrentUser());

        orderRepository.save(order);

        try {

            var session = paymentGateway.createCheckoutSession(order);
            cartService.clearCart(cart.getId());

            return new CheckoutResponse(order.getId(), session.getChekoutUrl());
        } catch (PaymentException ex) {
            orderRepository.delete(order);
            throw  ex;
        }
    }
}

package com.rumeshchathuranga.springapi.services;

import com.rumeshchathuranga.springapi.dtos.CheckoutRequest;
import com.rumeshchathuranga.springapi.dtos.CheckoutResponse;
import com.rumeshchathuranga.springapi.entities.Order;
import com.rumeshchathuranga.springapi.exceptions.CartEmptyException;
import com.rumeshchathuranga.springapi.exceptions.CartNotFoundException;
import com.rumeshchathuranga.springapi.repositories.CartRepository;
import com.rumeshchathuranga.springapi.repositories.OrderRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class CheckoutService {
    private CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final AuthService authService;
    private final CartService cartService;

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

        cartService.clearCart(cart.getId());

        return new CheckoutResponse(order.getId());
    }
}

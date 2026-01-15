package com.rumeshchathuranga.springapi.payments;

import com.rumeshchathuranga.springapi.orders.Order;
import com.rumeshchathuranga.springapi.carts.CartEmptyException;
import com.rumeshchathuranga.springapi.carts.CartNotFoundException;
import com.rumeshchathuranga.springapi.carts.CartRepository;
import com.rumeshchathuranga.springapi.orders.OrderRepository;
import com.rumeshchathuranga.springapi.auth.AuthService;
import com.rumeshchathuranga.springapi.carts.CartService;
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

    public void handleWebhookEvent(WebhookRequest request){
        paymentGateway.parseWebhookRequest(request)
                .ifPresent(paymentResult -> {
                    var order = orderRepository.findById(paymentResult.getOrderId()).orElseThrow();
                    order.setStatus(paymentResult.getPaymentStatus());
                    orderRepository.save(order);
                });
           }
}

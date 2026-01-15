package com.rumeshchathuranga.springapi.services;

import com.rumeshchathuranga.springapi.dtos.CheckoutRequest;
import com.rumeshchathuranga.springapi.dtos.CheckoutResponse;
import com.rumeshchathuranga.springapi.entities.Order;
import com.rumeshchathuranga.springapi.exceptions.CartEmptyException;
import com.rumeshchathuranga.springapi.exceptions.CartNotFoundException;
import com.rumeshchathuranga.springapi.repositories.CartRepository;
import com.rumeshchathuranga.springapi.repositories.OrderRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CheckoutService {
    private CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final AuthService authService;
    private final CartService cartService;

    @Value("${websiteUrl}")
    private String websiteUrl;

    public CheckoutResponse checkout(CheckoutRequest request) throws StripeException {
        var cart = cartRepository.getCartsWithItems(request.getCartId()).orElse(null);
        if (cart == null) {
            throw  new CartNotFoundException();
        }

        if(cart.isEmpty()){
            throw new CartEmptyException();
        }

        var order = Order.fromCart(cart, authService.getCurrentUser());

        orderRepository.save(order);

        //Create a checkout session
        var builder = SessionCreateParams.builder()
                        .setMode(SessionCreateParams.Mode.PAYMENT)
                        .setSuccessUrl(websiteUrl+"/checkout-success?orderId="+order.getId())
                        .setCancelUrl(websiteUrl+"/chekout-cancel");

        order.getItems().forEach(item -> {
            var lineItem = SessionCreateParams.LineItem.builder()
                    .setQuantity(Long.valueOf(item.getQuantity()))
                    .setPriceData(
                            SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency("usd")
                                    .setUnitAmountDecimal(item.getUnitPrice())
                                    .setProductData(
                                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                    .setName(item.getProduct().getName())
                                                    .build()
                                    ).build()


                    ).build();
            builder.addLineItem(lineItem);

        });

        var session = Session.create(builder.build());
        cartService.clearCart(cart.getId());

        return new CheckoutResponse(order.getId(), session.getUrl());
    }
}

package com.rumeshchathuranga.springapi.services;

import com.rumeshchathuranga.springapi.entities.Order;

public interface PaymentGateway {
    CheckoutSession createCheckoutSession(Order order);
}

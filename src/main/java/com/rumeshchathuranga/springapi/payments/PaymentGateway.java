package com.rumeshchathuranga.springapi.payments;

import com.rumeshchathuranga.springapi.orders.Order;

import java.util.Optional;

public interface PaymentGateway {
    CheckoutSession createCheckoutSession(Order order);
    Optional<PaymentResult> parseWebhookRequest(WebhookRequest request);
}

package com.rumeshchathuranga.springapi.controllers;

import com.rumeshchathuranga.springapi.dtos.CheckoutRequest;
import com.rumeshchathuranga.springapi.dtos.CheckoutResponse;
import com.rumeshchathuranga.springapi.dtos.ErrorDto;
import com.rumeshchathuranga.springapi.exceptions.CartEmptyException;
import com.rumeshchathuranga.springapi.exceptions.CartNotFoundException;
import com.rumeshchathuranga.springapi.exceptions.PaymentException;
import com.rumeshchathuranga.springapi.services.CheckoutService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/checkout")
public class CheckoutController {
    private final CheckoutService checkoutService;

    @Value("${stripe.webhookSecretKey}")
    private String webhookSecretKey;

    @PostMapping
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(request);
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader("Stripe-Signature") String signature,
            @RequestBody String payload
    ){
        try {
            var event = Webhook.constructEvent(payload,signature, webhookSecretKey);
            var stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
            switch (event.getType()) {
                case "Payment_intent.succeeded" -> {
                    //Update order status PAID
                }
                case "Payment_intent.failed" -> {
                    // Update order statud FAILED
                }
            }
            return ResponseEntity.ok().build();

        } catch (SignatureVerificationException e) {
           return ResponseEntity.badRequest().build();
        }
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<?> handlePaymentException() {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorDto("Error creating a checkout session"));
    }

    @ExceptionHandler({
            CartNotFoundException.class,
            CartEmptyException.class
    })
    public ResponseEntity<ErrorDto> handleException(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorDto(exception.getMessage()));
    }
}

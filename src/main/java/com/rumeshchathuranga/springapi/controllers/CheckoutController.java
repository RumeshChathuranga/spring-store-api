package com.rumeshchathuranga.springapi.controllers;

import com.rumeshchathuranga.springapi.dtos.CheckoutRequest;
import com.rumeshchathuranga.springapi.dtos.CheckoutResponse;
import com.rumeshchathuranga.springapi.dtos.ErrorDto;
import com.rumeshchathuranga.springapi.exceptions.CartEmptyException;
import com.rumeshchathuranga.springapi.exceptions.CartNotFoundException;
import com.rumeshchathuranga.springapi.exceptions.PaymentException;
import com.rumeshchathuranga.springapi.services.CheckoutService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/checkout")
public class CheckoutController {
    private final CheckoutService checkoutService;

    @PostMapping
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(request);
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

package com.vibevault.paymentgateway.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    @PostMapping("/initialize")
    public String initializePayment() {
        // Implementation for initializing a payment
        return null;
    }
}

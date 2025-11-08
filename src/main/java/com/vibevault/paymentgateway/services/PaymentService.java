package com.vibevault.paymentgateway.services;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    PaymentGatewaySelectorStrategy paymentGatewaySelectorStrategy;
    public PaymentService(PaymentGatewaySelectorStrategy paymentGatewaySelectorStrategy) {
        this.paymentGatewaySelectorStrategy = paymentGatewaySelectorStrategy;
    }

    public String initializePayment() {
        return paymentGatewaySelectorStrategy
                .getBestPaymentGateway()
                .generateLink();
    }
}

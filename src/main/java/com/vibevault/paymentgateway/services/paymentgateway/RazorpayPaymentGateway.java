package com.vibevault.paymentgateway.services.paymentgateway;

import org.springframework.stereotype.Service;

@Service
public class RazorpayPaymentGateway implements  PaymentGateway {
    @Override
    public String generateLink() {
        return "";
    }
}

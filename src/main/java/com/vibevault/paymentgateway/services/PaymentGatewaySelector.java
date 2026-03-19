package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.services.paymentgateway.PaymentGateway;
import com.vibevault.paymentgateway.services.paymentgateway.RazorpayPaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentGatewaySelector {

    private final RazorpayPaymentGateway razorpayPaymentGateway;

    public PaymentGateway getPaymentGateway() {
        return razorpayPaymentGateway;
    }
}

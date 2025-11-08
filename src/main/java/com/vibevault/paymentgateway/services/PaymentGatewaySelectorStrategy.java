package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.services.paymentgateway.PaymentGateway;
import com.vibevault.paymentgateway.services.paymentgateway.RazorpayPaymentGateway;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewaySelectorStrategy {
    private RazorpayPaymentGateway razorpayPaymentGateway;

    public  PaymentGatewaySelectorStrategy(RazorpayPaymentGateway razorpayPaymentGateway) {
        this.razorpayPaymentGateway = razorpayPaymentGateway;
    }
    public PaymentGateway getBestPaymentGateway() {
        return razorpayPaymentGateway;
    }
}

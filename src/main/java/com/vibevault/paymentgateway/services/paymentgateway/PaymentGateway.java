package com.vibevault.paymentgateway.services.paymentgateway;

import com.vibevault.paymentgateway.dtos.PaymentLinkRequest;
import com.vibevault.paymentgateway.dtos.PaymentLinkResponse;

public interface PaymentGateway {
    PaymentLinkResponse createPaymentLink(PaymentLinkRequest request);
    boolean verifyWebhookSignature(String payload, String signature);
    String getGatewayName();
}

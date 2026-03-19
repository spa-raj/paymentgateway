package com.vibevault.paymentgateway.services.paymentgateway;

import com.razorpay.PaymentLink;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import com.vibevault.paymentgateway.configurations.RazorpayConfig;
import com.vibevault.paymentgateway.dtos.PaymentLinkRequest;
import com.vibevault.paymentgateway.dtos.PaymentLinkResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayPaymentGateway implements PaymentGateway {

    private final RazorpayClient razorpayClient;
    private final RazorpayConfig razorpayConfig;

    @Override
    public PaymentLinkResponse createPaymentLink(PaymentLinkRequest request) {
        try {
            JSONObject paymentLinkRequest = new JSONObject();
            // Razorpay expects amount in paise (1 INR = 100 paise)
            paymentLinkRequest.put("amount", request.getAmount().movePointRight(2).intValue());
            paymentLinkRequest.put("currency", request.getCurrency() != null ? request.getCurrency() : "INR");
            paymentLinkRequest.put("accept_partial", false);
            paymentLinkRequest.put("reference_id", request.getOrderId().toString());
            paymentLinkRequest.put("description", request.getDescription() != null
                    ? request.getDescription()
                    : "Payment for order " + request.getOrderId());

            if (request.getCustomerName() != null || request.getCustomerEmail() != null) {
                JSONObject customer = new JSONObject();
                if (request.getCustomerName() != null) customer.put("name", request.getCustomerName());
                if (request.getCustomerEmail() != null) customer.put("email", request.getCustomerEmail());
                if (request.getCustomerPhone() != null) customer.put("contact", request.getCustomerPhone());
                paymentLinkRequest.put("customer", customer);
            }

            JSONObject notify = new JSONObject();
            notify.put("sms", request.getCustomerPhone() != null);
            notify.put("email", request.getCustomerEmail() != null);
            paymentLinkRequest.put("notify", notify);

            paymentLinkRequest.put("reminder_enable", true);

            JSONObject notes = new JSONObject();
            notes.put("order_id", request.getOrderId().toString());
            notes.put("user_id", request.getUserId());
            paymentLinkRequest.put("notes", notes);

            PaymentLink paymentLink = razorpayClient.paymentLink.create(paymentLinkRequest);

            String linkId = paymentLink.get("id");
            String shortUrl = paymentLink.get("short_url");

            log.info("Razorpay payment link created: {} for order {}", linkId, request.getOrderId());

            return PaymentLinkResponse.builder()
                    .paymentId(null) // will be set by the service layer
                    .paymentLink(shortUrl)
                    .gateway(getGatewayName())
                    .gatewayPaymentId(linkId)
                    .build();

        } catch (RazorpayException e) {
            log.error("Failed to create Razorpay payment link for order {}: {}", request.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("Failed to create payment link: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        try {
            return Utils.verifyWebhookSignature(payload, signature, razorpayConfig.getWebhookSecret());
        } catch (RazorpayException e) {
            log.error("Webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getGatewayName() {
        return "RAZORPAY";
    }
}

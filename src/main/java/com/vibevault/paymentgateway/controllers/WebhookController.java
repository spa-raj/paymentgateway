package com.vibevault.paymentgateway.controllers;

import com.vibevault.paymentgateway.exceptions.InvalidPaymentStateException;
import com.vibevault.paymentgateway.exceptions.PaymentNotFoundException;
import com.vibevault.paymentgateway.dtos.PaymentTransitionResult;
import com.vibevault.paymentgateway.services.PaymentEventProducer;
import com.vibevault.paymentgateway.services.PaymentService;
import com.vibevault.paymentgateway.services.paymentgateway.PaymentGateway;
import com.vibevault.paymentgateway.services.PaymentGatewaySelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/payments/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService paymentService;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentGatewaySelector paymentGatewaySelector;

    @PostMapping("/razorpay")
    public ResponseEntity<String> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Razorpay-Signature") String signature) {

        log.info("Received Razorpay webhook");

        PaymentGateway gateway = paymentGatewaySelector.getPaymentGateway();

        if (!gateway.verifyWebhookSignature(payload, signature)) {
            log.warn("Invalid Razorpay webhook signature");
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        try {
            JSONObject webhookPayload = new JSONObject(payload);
            String event = webhookPayload.getString("event");

            if (!event.startsWith("payment_link.")) {
                log.debug("Ignoring non-payment-link webhook event: {}", event);
                return ResponseEntity.ok("OK");
            }

            JSONObject paymentLinkEntity = webhookPayload
                    .getJSONObject("payload")
                    .getJSONObject("payment_link")
                    .getJSONObject("entity");
            String paymentLinkId = paymentLinkEntity.getString("id");

            log.info("Razorpay webhook event: {} for payment link: {}", event, paymentLinkId);

            switch (event) {
                case "payment_link.paid" -> {
                    PaymentTransitionResult result = paymentService.confirmPayment(paymentLinkId);
                    if (result.stateChanged()) {
                        paymentEventProducer.sendPaymentConfirmed(result.payment());
                        log.info("Payment {} confirmed via webhook — event published", result.payment().getId());
                    } else {
                        log.info("Payment {} already confirmed — skipping duplicate event", result.payment().getId());
                    }
                }
                case "payment_link.expired", "payment_link.cancelled" -> {
                    PaymentTransitionResult result = paymentService.failPayment(paymentLinkId, "Payment link " + event.replace("payment_link.", ""));
                    if (result.stateChanged()) {
                        paymentEventProducer.sendPaymentFailed(result.payment());
                        log.info("Payment {} failed via webhook — event published", result.payment().getId());
                    } else {
                        log.info("Payment {} already failed — skipping duplicate event", result.payment().getId());
                    }
                }
                default -> log.debug("Ignoring Razorpay webhook event: {}", event);
            }

            return ResponseEntity.ok("OK");

        } catch (InvalidPaymentStateException | PaymentNotFoundException e) {
            log.info("Webhook processed as no-op: {}", e.getMessage());
            return ResponseEntity.ok("OK");
        } catch (JSONException e) {
            log.warn("Invalid webhook payload format: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid payload");
        } catch (Exception e) {
            log.error("Error processing Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing failed");
        }
    }
}

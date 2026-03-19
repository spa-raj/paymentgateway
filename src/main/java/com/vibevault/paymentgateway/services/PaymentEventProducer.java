package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.constants.KafkaTopics;
import com.vibevault.paymentgateway.events.PaymentEvent;
import com.vibevault.paymentgateway.models.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventProducer {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    public void sendPaymentConfirmed(Payment payment) {
        send(PaymentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("PAYMENT_CONFIRMED")
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .timestamp(LocalDateTime.now())
                .build());
    }

    public void sendPaymentFailed(Payment payment) {
        send(PaymentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("PAYMENT_FAILED")
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .timestamp(LocalDateTime.now())
                .failureReason(payment.getFailureReason())
                .build());
    }

    private void send(PaymentEvent event) {
        try {
            kafkaTemplate.send(KafkaTopics.PAYMENT_EVENTS, event.getUserId(), event);
            log.debug("Payment event sent: {} for order {}", event.getEventType(), event.getOrderId());
        } catch (Exception e) {
            log.warn("Failed to send payment event {} for order {}: {}",
                    event.getEventType(), event.getOrderId(), e.getMessage());
        }
    }
}

package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.constants.KafkaTopics;
import com.vibevault.paymentgateway.dtos.PaymentLinkRequest;
import com.vibevault.paymentgateway.events.OrderEvent;
import com.vibevault.paymentgateway.models.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final PaymentService paymentService;

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENTS,
            groupId = "paymentgateway",
            containerFactory = "orderEventListenerContainerFactory"
    )
    public void handleOrderEvent(OrderEvent event) {
        if (!"ORDER_CREATED".equals(event.getEventType())) {
            log.debug("Ignoring order event type: {}", event.getEventType());
            return;
        }

        log.info("Processing ORDER_CREATED event {} for order {}", event.getEventId(), event.getOrderId());

        try {
            PaymentLinkRequest request = PaymentLinkRequest.builder()
                    .orderId(event.getOrderId())
                    .userId(event.getUserId())
                    .amount(event.getTotalAmount())
                    .currency(event.getCurrency())
                    .build();

            Payment payment = paymentService.createPayment(request, event.getEventId());
            log.info("Payment {} created with link: {}", payment.getId(), payment.getGatewayPaymentLink());
        } catch (Exception e) {
            log.error("Failed to process ORDER_CREATED event {} for order {}: {}",
                    event.getEventId(), event.getOrderId(), e.getMessage(), e);
            throw e; // rethrow so Kafka retries (createPayment is idempotent)
        }
    }
}

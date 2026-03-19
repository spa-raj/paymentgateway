package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.dtos.PaymentLinkRequest;
import com.vibevault.paymentgateway.dtos.PaymentLinkResponse;
import com.vibevault.paymentgateway.exceptions.InvalidPaymentStateException;
import com.vibevault.paymentgateway.exceptions.PaymentNotFoundException;
import com.vibevault.paymentgateway.models.Payment;
import com.vibevault.paymentgateway.models.PaymentStatus;
import com.vibevault.paymentgateway.repositories.PaymentRepository;
import com.vibevault.paymentgateway.services.paymentgateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGatewaySelector paymentGatewaySelector;

    @Transactional
    public Payment createPayment(PaymentLinkRequest request, String orderEventId) {
        if (orderEventId == null || orderEventId.isBlank()) {
            throw new IllegalArgumentException("orderEventId must not be null or blank");
        }

        // Idempotency check
        Optional<Payment> existing = paymentRepository.findByOrderEventId(orderEventId);
        if (existing.isPresent()) {
            log.warn("Duplicate order event {} — returning existing payment {}", orderEventId, existing.get().getId());
            return existing.get();
        }

        PaymentGateway gateway = paymentGatewaySelector.getPaymentGateway();

        // Persist PENDING payment first (reserves the unique constraints)
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .userId(request.getUserId())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .status(PaymentStatus.PENDING)
                .gateway(gateway.getGatewayName())
                .orderEventId(orderEventId)
                .build();

        try {
            payment = paymentRepository.save(payment);
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert won — return the existing payment
            log.warn("Concurrent payment creation for order event {} — returning existing", orderEventId);
            return paymentRepository.findByOrderEventId(orderEventId)
                    .orElseThrow(() -> new IllegalStateException("Payment disappeared after constraint violation", e));
        }

        // Create payment link via gateway (after DB insert guarantees uniqueness)
        PaymentLinkResponse linkResponse = gateway.createPaymentLink(request);
        payment.setGatewayPaymentId(linkResponse.getGatewayPaymentId());
        payment.setGatewayPaymentLink(linkResponse.getPaymentLink());
        payment = paymentRepository.save(payment);

        log.info("Payment {} created for order {} via {} — link: {}",
                payment.getId(), payment.getOrderId(), payment.getGateway(), payment.getGatewayPaymentLink());
        return payment;
    }

    @Transactional
    public Payment confirmPayment(String gatewayPaymentId) {
        Payment payment = paymentRepository.findByGatewayPaymentId(gatewayPaymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for gateway ID: " + gatewayPaymentId));

        if (payment.getStatus() == PaymentStatus.CONFIRMED) {
            log.info("Payment {} already confirmed — no-op", payment.getId());
            return payment;
        }
        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new InvalidPaymentStateException("Cannot confirm payment " + payment.getId() + " — already FAILED");
        }

        payment.setStatus(PaymentStatus.CONFIRMED);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment {} confirmed for order {}", saved.getId(), saved.getOrderId());
        return saved;
    }

    @Transactional
    public Payment failPayment(String gatewayPaymentId, String reason) {
        Payment payment = paymentRepository.findByGatewayPaymentId(gatewayPaymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for gateway ID: " + gatewayPaymentId));

        if (payment.getStatus() == PaymentStatus.FAILED) {
            log.info("Payment {} already failed — no-op", payment.getId());
            return payment;
        }
        if (payment.getStatus() == PaymentStatus.CONFIRMED) {
            throw new InvalidPaymentStateException("Cannot fail payment " + payment.getId() + " — already CONFIRMED");
        }

        log.info("Failing payment {} — reason: {}", payment.getId(), reason);
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment getPaymentByOrderId(UUID orderId, String userId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for order: " + orderId));
        if (!payment.getUserId().equals(userId)) {
            throw new PaymentNotFoundException("Payment not found for order: " + orderId);
        }
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getPaymentById(UUID paymentId, String userId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!payment.getUserId().equals(userId)) {
            throw new PaymentNotFoundException(paymentId);
        }
        return payment;
    }
}

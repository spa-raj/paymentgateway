package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.dtos.PaymentLinkRequest;
import com.vibevault.paymentgateway.dtos.PaymentLinkResponse;
import com.vibevault.paymentgateway.exceptions.PaymentNotFoundException;
import com.vibevault.paymentgateway.models.Payment;
import com.vibevault.paymentgateway.models.PaymentStatus;
import com.vibevault.paymentgateway.repositories.PaymentRepository;
import com.vibevault.paymentgateway.services.paymentgateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        // Idempotency check
        Optional<Payment> existing = paymentRepository.findByOrderEventId(orderEventId);
        if (existing.isPresent()) {
            log.warn("Duplicate order event {} — returning existing payment {}", orderEventId, existing.get().getId());
            return existing.get();
        }

        PaymentGateway gateway = paymentGatewaySelector.getPaymentGateway();
        PaymentLinkResponse linkResponse = gateway.createPaymentLink(request);

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .userId(request.getUserId())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .status(PaymentStatus.PENDING)
                .gateway(gateway.getGatewayName())
                .gatewayPaymentId(linkResponse.getGatewayPaymentId())
                .gatewayPaymentLink(linkResponse.getPaymentLink())
                .orderEventId(orderEventId)
                .build();

        Payment saved = paymentRepository.save(payment);
        log.info("Payment {} created for order {} via {} — link: {}",
                saved.getId(), saved.getOrderId(), saved.getGateway(), saved.getGatewayPaymentLink());
        return saved;
    }

    @Transactional
    public Payment confirmPayment(String gatewayPaymentId) {
        Payment payment = paymentRepository.findByGatewayPaymentId(gatewayPaymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for gateway ID: " + gatewayPaymentId));

        if (payment.getStatus() == PaymentStatus.CONFIRMED) {
            log.info("Payment {} already confirmed — no-op", payment.getId());
            return payment;
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

        log.info("Failing payment {} — reason: {}", payment.getId(), reason);
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        Payment saved = paymentRepository.save(payment);
        return saved;
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

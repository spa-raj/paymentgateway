package com.vibevault.paymentgateway.services;

import com.vibevault.paymentgateway.dtos.PaymentLinkRequest;
import com.vibevault.paymentgateway.dtos.PaymentLinkResponse;
import com.vibevault.paymentgateway.dtos.PaymentTransitionResult;
import com.vibevault.paymentgateway.exceptions.InvalidPaymentStateException;
import com.vibevault.paymentgateway.exceptions.PaymentNotFoundException;
import com.vibevault.paymentgateway.models.Payment;
import com.vibevault.paymentgateway.models.PaymentStatus;
import com.vibevault.paymentgateway.repositories.PaymentRepository;
import com.vibevault.paymentgateway.services.paymentgateway.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewaySelector paymentGatewaySelector;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private PaymentService paymentService;

    private PaymentLinkRequest buildRequest() {
        return PaymentLinkRequest.builder()
                .orderId(UUID.randomUUID())
                .userId("user-1")
                .amount(new BigDecimal("999.98"))
                .currency("INR")
                .build();
    }

    private Payment buildPayment(UUID id, PaymentStatus status) {
        Payment payment = Payment.builder()
                .orderId(UUID.randomUUID())
                .userId("user-1")
                .amount(new BigDecimal("999.98"))
                .currency("INR")
                .status(status)
                .gateway("RAZORPAY")
                .gatewayPaymentId("plink_test123")
                .gatewayPaymentLink("https://rzp.io/test")
                .orderEventId("event-1")
                .build();
        payment.setId(id);
        return payment;
    }

    // --- createPayment ---

    @Test
    void createPayment_happyPath() {
        PaymentLinkRequest request = buildRequest();
        when(paymentRepository.findByOrderEventId("event-1")).thenReturn(Optional.empty());
        when(paymentGatewaySelector.getPaymentGateway()).thenReturn(paymentGateway);
        when(paymentGateway.getGatewayName()).thenReturn("RAZORPAY");
        when(paymentGateway.createPaymentLink(request)).thenReturn(
                PaymentLinkResponse.builder()
                        .gatewayPaymentId("plink_123")
                        .paymentLink("https://rzp.io/test")
                        .gateway("RAZORPAY")
                        .build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });

        Payment result = paymentService.createPayment(request, "event-1");

        assertNotNull(result.getId());
        assertEquals(PaymentStatus.PENDING, result.getStatus());
        assertEquals("RAZORPAY", result.getGateway());
        assertEquals("plink_123", result.getGatewayPaymentId());
        verify(paymentRepository, times(2)).save(any()); // once for PENDING, once for link update
    }

    @Test
    void createPayment_duplicate_returnsExisting() {
        UUID existingId = UUID.randomUUID();
        Payment existing = buildPayment(existingId, PaymentStatus.PENDING);
        when(paymentRepository.findByOrderEventId("event-1")).thenReturn(Optional.of(existing));

        Payment result = paymentService.createPayment(buildRequest(), "event-1");

        assertEquals(existingId, result.getId());
        verify(paymentGatewaySelector, never()).getPaymentGateway();
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void createPayment_nullOrderEventId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentService.createPayment(buildRequest(), null));
    }

    @Test
    void createPayment_blankOrderEventId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> paymentService.createPayment(buildRequest(), "  "));
    }

    // --- confirmPayment ---

    @Test
    void confirmPayment_pendingToConfirmed() {
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.PENDING);
        when(paymentRepository.findByGatewayPaymentId("plink_test123")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentTransitionResult result = paymentService.confirmPayment("plink_test123");

        assertTrue(result.stateChanged());
        assertEquals(PaymentStatus.CONFIRMED, result.payment().getStatus());
    }

    @Test
    void confirmPayment_alreadyConfirmed_noOp() {
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.CONFIRMED);
        when(paymentRepository.findByGatewayPaymentId("plink_test123")).thenReturn(Optional.of(payment));

        PaymentTransitionResult result = paymentService.confirmPayment("plink_test123");

        assertFalse(result.stateChanged());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void confirmPayment_failedPayment_throws() {
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.FAILED);
        when(paymentRepository.findByGatewayPaymentId("plink_test123")).thenReturn(Optional.of(payment));

        assertThrows(InvalidPaymentStateException.class,
                () -> paymentService.confirmPayment("plink_test123"));
    }

    // --- failPayment ---

    @Test
    void failPayment_pendingToFailed() {
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.PENDING);
        when(paymentRepository.findByGatewayPaymentId("plink_test123")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentTransitionResult result = paymentService.failPayment("plink_test123", "expired");

        assertTrue(result.stateChanged());
        assertEquals(PaymentStatus.FAILED, result.payment().getStatus());
        assertEquals("expired", result.payment().getFailureReason());
    }

    @Test
    void failPayment_alreadyFailed_noOp() {
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.FAILED);
        when(paymentRepository.findByGatewayPaymentId("plink_test123")).thenReturn(Optional.of(payment));

        PaymentTransitionResult result = paymentService.failPayment("plink_test123", "expired");

        assertFalse(result.stateChanged());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void failPayment_confirmedPayment_throws() {
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.CONFIRMED);
        when(paymentRepository.findByGatewayPaymentId("plink_test123")).thenReturn(Optional.of(payment));

        assertThrows(InvalidPaymentStateException.class,
                () -> paymentService.failPayment("plink_test123", "expired"));
    }

    // --- getPaymentByOrderId ---

    @Test
    void getPaymentByOrderId_ownerAccess() {
        UUID orderId = UUID.randomUUID();
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.PENDING);
        payment.setOrderId(orderId);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        Payment result = paymentService.getPaymentByOrderId(orderId, "user-1");

        assertEquals(orderId, result.getOrderId());
    }

    @Test
    void getPaymentByOrderId_nonOwner_throws() {
        UUID orderId = UUID.randomUUID();
        Payment payment = buildPayment(UUID.randomUUID(), PaymentStatus.PENDING);
        payment.setOrderId(orderId);
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

        assertThrows(PaymentNotFoundException.class,
                () -> paymentService.getPaymentByOrderId(orderId, "user-2"));
    }

    @Test
    void getPaymentByOrderId_notFound_throws() {
        UUID orderId = UUID.randomUUID();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class,
                () -> paymentService.getPaymentByOrderId(orderId, "user-1"));
    }
}

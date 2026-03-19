package com.vibevault.paymentgateway.controllers;

import com.vibevault.paymentgateway.exceptions.PaymentNotFoundException;
import com.vibevault.paymentgateway.models.Payment;
import com.vibevault.paymentgateway.models.PaymentStatus;
import com.vibevault.paymentgateway.security.SecurityConfig;
import com.vibevault.paymentgateway.services.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private Payment buildPayment(UUID id, UUID orderId) {
        Payment payment = Payment.builder()
                .orderId(orderId)
                .userId("user-1")
                .amount(new BigDecimal("999.98"))
                .currency("INR")
                .status(PaymentStatus.PENDING)
                .gateway("RAZORPAY")
                .gatewayPaymentLink("https://rzp.io/test")
                .build();
        payment.setId(id);
        return payment;
    }

    @Test
    void getPaymentByOrderId_returnsPayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, orderId);
        when(paymentService.getPaymentByOrderId(orderId, "user-1")).thenReturn(payment);

        mockMvc.perform(get("/payments/order/{orderId}", orderId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.gateway").value("RAZORPAY"));
    }

    @Test
    void getPaymentById_returnsPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Payment payment = buildPayment(paymentId, orderId);
        when(paymentService.getPaymentById(paymentId, "user-1")).thenReturn(payment);

        mockMvc.perform(get("/payments/{paymentId}", paymentId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));
    }

    @Test
    void getPayment_notFound_returns404() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(paymentService.getPaymentByOrderId(orderId, "user-1"))
                .thenThrow(new PaymentNotFoundException("Payment not found for order: " + orderId));

        mockMvc.perform(get("/payments/order/{orderId}", orderId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getPayment_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/payments/order/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}

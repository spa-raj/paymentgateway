package com.vibevault.paymentgateway.controllers;

import com.vibevault.paymentgateway.dtos.PaymentResponseDto;
import com.vibevault.paymentgateway.models.Payment;
import com.vibevault.paymentgateway.services.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping("/order/{orderId}")
    public PaymentResponseDto getPaymentByOrderId(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        Payment payment = paymentService.getPaymentByOrderId(orderId, jwt.getSubject());
        return PaymentResponseDto.fromPayment(payment);
    }

    @GetMapping("/{paymentId}")
    public PaymentResponseDto getPaymentById(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        Payment payment = paymentService.getPaymentById(paymentId, jwt.getSubject());
        return PaymentResponseDto.fromPayment(payment);
    }
}

package com.vibevault.paymentgateway.dtos;

import com.vibevault.paymentgateway.models.Payment;

public record PaymentTransitionResult(Payment payment, boolean stateChanged) {
}

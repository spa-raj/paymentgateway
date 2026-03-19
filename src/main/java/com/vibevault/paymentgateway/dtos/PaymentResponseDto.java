package com.vibevault.paymentgateway.dtos;

import com.vibevault.paymentgateway.models.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentResponseDto {
    private UUID paymentId;
    private UUID orderId;
    private String userId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String gateway;
    private String gatewayPaymentLink;
    private Date createdAt;

    public static PaymentResponseDto fromPayment(Payment payment) {
        return PaymentResponseDto.builder()
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .gateway(payment.getGateway())
                .gatewayPaymentLink(payment.getGatewayPaymentLink())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}

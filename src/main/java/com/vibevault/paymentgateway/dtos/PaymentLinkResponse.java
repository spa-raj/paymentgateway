package com.vibevault.paymentgateway.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentLinkResponse {
    private UUID paymentId;
    private String paymentLink;
    private String gateway;
    private String gatewayPaymentId;
}

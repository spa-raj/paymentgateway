package com.vibevault.paymentgateway.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEvent {
    private String eventId;
    private String eventType;
    private UUID orderId;
    private String userId;
    private BigDecimal amount;
    private LocalDateTime timestamp;
    private String failureReason;
}

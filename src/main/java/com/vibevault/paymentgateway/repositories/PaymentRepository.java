package com.vibevault.paymentgateway.repositories;

import com.vibevault.paymentgateway.models.Payment;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@NullMarked
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByOrderEventId(String orderEventId);

    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);
}

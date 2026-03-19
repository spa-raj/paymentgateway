package com.vibevault.paymentgateway.configurations;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Configuration
@ConfigurationProperties(prefix = "razorpay")
public class RazorpayConfig {

    @NotBlank(message = "razorpay.key-id must be configured")
    private String keyId;

    @NotBlank(message = "razorpay.key-secret must be configured")
    private String keySecret;

    private String webhookSecret;

    @Bean
    public RazorpayClient razorpayClient() throws RazorpayException {
        return new RazorpayClient(keyId, keySecret);
    }
}

package com.vibevault.paymentgateway.dtos.exceptions;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExceptionDto {
    private String httpStatus;
    private String message;
    private String path;
    private String errorCode;
    private LocalDateTime timestamp;
}

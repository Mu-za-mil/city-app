package com.cityapp.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OtpResponse {
    private boolean sent;
    private String message;
    private String otp;  // dev only
}
package com.cityapp.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OtpResponse {
    private boolean sent;
    private String message;
}

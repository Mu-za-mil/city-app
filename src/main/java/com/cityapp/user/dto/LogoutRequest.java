package com.cityapp.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LogoutRequest {
    // Optional: include the refresh token to also revoke the DB session.
    // If null: only the access token is blacklisted in Redis.
    private String refreshToken;
}
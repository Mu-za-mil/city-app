package com.cityapp.auth.service;

import com.cityapp.auth.dto.VerifyOtpRequest;
import com.cityapp.auth.repository.UserRepository;
import com.cityapp.auth.service.RefreshTokenService;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.exception.AppException;
import com.cityapp.security.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceVerificationTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private UserRepository userRepository;
    @Mock private EventPublisher eventPublisher;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private JwtService jwtService;
    @Mock private HttpServletRequest httpRequest;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(
                redisTemplate, userRepository, eventPublisher,
                refreshTokenService, jwtService);
    }

    @Test
    void wrongOtpIsRejectedAndDoesNotIssueTokens() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        AppException exception = assertThrows(AppException.class,
                () -> otpService.verifyOtp(request("000000"), httpRequest));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("Invalid OTP. Please check and try again.", exception.getMessage());
        verifyNoInteractions(userRepository, refreshTokenService, jwtService);
    }

    @Test
    void attemptLimitReturnsTooManyRequestsAndDoesNotIssueTokens() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-2L);

        AppException exception = assertThrows(AppException.class,
                () -> otpService.verifyOtp(request("123456"), httpRequest));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatus());
        assertEquals("OTP_ATTEMPTS_EXCEEDED", exception.getErrorCode());
        verifyNoInteractions(userRepository, refreshTokenService, jwtService);
    }

    @Test
    void missingOrExpiredOtpIsRejectedAndDoesNotIssueTokens() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-1L);

        AppException exception = assertThrows(AppException.class,
                () -> otpService.verifyOtp(request("123456"), httpRequest));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("expired or was never requested"));
        verifyNoInteractions(userRepository, refreshTokenService, jwtService);
    }

    @Test
    void unexpectedRedisResultFailsClosed() {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

        AppException exception = assertThrows(AppException.class,
                () -> otpService.verifyOtp(request("123456"), httpRequest));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(userRepository, refreshTokenService, jwtService);
    }

    private VerifyOtpRequest request(String otp) {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setPhone("9876543210");
        request.setOtp(otp);
        return request;
    }
}

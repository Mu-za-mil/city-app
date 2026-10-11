package com.cityapp.auth.service;

import com.cityapp.auth.dto.UserResponse;
import com.cityapp.auth.entity.User;
import com.cityapp.auth.mapper.UserMapper;
import com.cityapp.auth.repository.UserRepository;
import com.cityapp.common.enums.Role;
import com.cityapp.common.exception.AppException;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.security.service.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceAccountStatusTest {

    @Mock private UserRepository userRepository;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private EventPublisher eventPublisher;

    @InjectMocks private UserService userService;

    @Test
    void suspendUserDisablesAccountAndRevokesRefreshSessions() {
        User user = User.builder()
                .id(42L)
                .role(Role.USER)
                .enabled(true)
                .build();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userMapper.toResponse(user)).thenReturn(
                UserResponse.builder().id(42L).enabled(false).build());
        when(refreshTokenService.revokeAllForUser(42L)).thenReturn(2);

        UserResponse response = userService.suspendUser(42L);

        assertFalse(user.isEnabled());
        assertFalse(response.isEnabled());
        verify(userRepository).save(user);
        verify(refreshTokenService).revokeAllForUser(42L);
    }

    @Test
    void suspendUserRejectsSuperAdminWithoutChangingStatusOrRevokingSessions() {
        User user = User.builder()
                .id(7L)
                .role(Role.SUPER_ADMIN)
                .enabled(true)
                .build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThrows(AppException.class, () -> userService.suspendUser(7L));

        assertTrue(user.isEnabled());
        verify(userRepository, never()).save(any(User.class));
        verify(refreshTokenService, never()).revokeAllForUser(anyLong());
    }
}

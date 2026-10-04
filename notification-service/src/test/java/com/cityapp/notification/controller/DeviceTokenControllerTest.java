package com.cityapp.notification.controller;

import com.cityapp.common.exception.AppException;
import com.cityapp.notification.entity.DeviceToken;
import com.cityapp.notification.entity.User;
import com.cityapp.notification.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceTokenControllerTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private DeviceTokenController controller;

    @Test
    void registerToken_shouldRejectTokenOwnedByAnotherUser() {
        User user = User.builder().id(1L).email("user1@example.com").build();
        DeviceToken existing = DeviceToken.builder()
                .id(10L)
                .userId(2L)
                .token("token-b")
                .platform("ANDROID")
                .active(true)
                .build();

        when(deviceTokenRepository.findByToken("token-b"))
                .thenReturn(Optional.of(existing));

        DeviceTokenController.RegisterTokenRequest request =
                request("token-b", "ANDROID");

        AppException exception = assertThrows(
                AppException.class,
                () -> controller.registerToken(user, request));

        assertEquals(403, exception.getStatus().value());
        verify(deviceTokenRepository, never()).save(any());
    }

    @Test
    void registerToken_shouldAllowOwnerAndReactivateToken() {
        User user = User.builder().id(1L).email("user1@example.com").build();
        DeviceToken existing = DeviceToken.builder()
                .id(10L)
                .userId(1L)
                .token("token-a")
                .platform("ANDROID")
                .active(false)
                .build();

        when(deviceTokenRepository.findByToken("token-a"))
                .thenReturn(Optional.of(existing));

        controller.registerToken(user, request("token-a", "IOS"));

        assertEquals(true, existing.isActive());
        assertEquals("IOS", existing.getPlatform());
        verify(deviceTokenRepository).save(existing);
    }

    @Test
    void unregisterToken_shouldRejectTokenOwnedByAnotherUser() {
        User user = User.builder().id(1L).email("user1@example.com").build();
        DeviceToken existing = DeviceToken.builder()
                .id(10L)
                .userId(2L)
                .token("token-b")
                .platform("ANDROID")
                .active(true)
                .build();

        when(deviceTokenRepository.findByToken("token-b"))
                .thenReturn(Optional.of(existing));

        AppException exception = assertThrows(
                AppException.class,
                () -> controller.unregisterToken(user, "token-b"));

        assertEquals(403, exception.getStatus().value());
        verify(deviceTokenRepository, never()).deactivateToken(anyString());
    }

    @Test
    void unregisterToken_shouldDeactivateOwnedToken() {
        User user = User.builder().id(1L).email("user1@example.com").build();
        DeviceToken existing = DeviceToken.builder()
                .id(10L)
                .userId(1L)
                .token("token-a")
                .platform("ANDROID")
                .active(true)
                .build();

        when(deviceTokenRepository.findByToken("token-a"))
                .thenReturn(Optional.of(existing));

        controller.unregisterToken(user, "token-a");

        verify(deviceTokenRepository).deactivateToken("token-a");
    }

    @Test
    void unregisterToken_shouldReturnNotFoundForUnknownToken() {
        User user = User.builder().id(1L).email("user1@example.com").build();

        when(deviceTokenRepository.findByToken("missing"))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> controller.unregisterToken(user, "missing"));

        assertEquals(404, exception.getStatus().value());
        verify(deviceTokenRepository, never()).deactivateToken(anyString());
    }

    private static DeviceTokenController.RegisterTokenRequest request(
            String token, String platform) {
        DeviceTokenController.RegisterTokenRequest request =
                new DeviceTokenController.RegisterTokenRequest();
        request.setToken(token);
        request.setPlatform(platform);
        return request;
    }
}

package com.cityapp.notification.controller;

import com.cityapp.common.exception.AppException;
import com.cityapp.notification.entity.DeviceToken;
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
                () -> controller.registerToken("1", request("token-b", "ANDROID")));

        assertEquals(403, exception.getStatus().value());
        verify(deviceTokenRepository, never()).save(any());
    }

    @Test
    void registerToken_shouldAllowOwnerAndReactivateToken() {
        DeviceToken existing = DeviceToken.builder()
                .id(10L)
                .userId(1L)
                .token("token-a")
                .platform("ANDROID")
                .active(false)
                .build();

        when(deviceTokenRepository.findByToken("token-a"))
                .thenReturn(Optional.of(existing));

        controller.registerToken("1", request("token-a", "IOS"));

        assertEquals(true, existing.isActive());
        assertEquals("IOS", existing.getPlatform());
        verify(deviceTokenRepository).save(existing);
    }

    @Test
    void registerToken_shouldPersistAuthenticatedUserIdForNewToken() {
        when(deviceTokenRepository.findByToken("token-new"))
                .thenReturn(Optional.empty());

        controller.registerToken("123", request("token-new", "WEB"));

        verify(deviceTokenRepository).save(argThat(token ->
                token.getUserId().equals(123L)
                        && token.getToken().equals("token-new")
                        && token.isActive()));
    }

    @Test
    void registerToken_shouldRejectMalformedAuthenticatedUserId() {
        AppException exception = assertThrows(
                AppException.class,
                () -> controller.registerToken("not-a-number", request("token-a", "IOS")));

        assertEquals(403, exception.getStatus().value());
        verifyNoInteractions(deviceTokenRepository);
    }

    @Test
    void unregisterToken_shouldRejectTokenOwnedByAnotherUser() {
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
                () -> controller.unregisterToken("1", "token-b"));

        assertEquals(403, exception.getStatus().value());
        verify(deviceTokenRepository, never()).deactivateToken(anyString());
    }

    @Test
    void unregisterToken_shouldDeactivateOwnedToken() {
        DeviceToken existing = DeviceToken.builder()
                .id(10L)
                .userId(1L)
                .token("token-a")
                .platform("ANDROID")
                .active(true)
                .build();

        when(deviceTokenRepository.findByToken("token-a"))
                .thenReturn(Optional.of(existing));

        controller.unregisterToken("1", "token-a");

        verify(deviceTokenRepository).deactivateToken("token-a");
    }

    @Test
    void unregisterToken_shouldReturnNotFoundForUnknownToken() {
        when(deviceTokenRepository.findByToken("missing"))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> controller.unregisterToken("1", "missing"));

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

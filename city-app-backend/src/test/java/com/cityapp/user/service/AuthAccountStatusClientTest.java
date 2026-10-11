package com.cityapp.user.service;

import com.cityapp.user.dto.UserResponse;
import com.cityapp.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AuthAccountStatusClientTest {

    private MockRestServiceServer server;
    private AuthAccountStatusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AuthAccountStatusClient(builder, "http://auth-service:8081");
    }

    @Test
    void suspendForwardsBearerTokenAndReturnsAuthServiceUser() {
        server.expect(requestTo(
                        "http://auth-service:8081/api/v1/auth/admin/users/42/suspend"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-token"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "message": "User suspended successfully",
                          "data": {
                            "id": 42,
                            "name": "Test User",
                            "email": "test@example.com",
                            "phone": null,
                            "role": "USER",
                            "enabled": false,
                            "profileImageUrl": null,
                            "createdAt": "2026-01-01T00:00:00Z"
                          },
                          "timestamp": "2026-01-01T00:00:00Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        UserResponse response = client.suspend(42L, "Bearer signed-token");

        assertEquals(42L, response.getId());
        assertEquals(Role.USER, response.getRole());
        assertFalse(response.isEnabled());
        server.verify();
    }

    @Test
    void reinstatePreservesAuthServiceAuthorizationFailure() {
        server.expect(requestTo(
                        "http://auth-service:8081/api/v1/auth/admin/users/42/reinstate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> client.reinstate(42L, "Bearer signed-token"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        server.verify();
    }

    @Test
    void rejectsMissingBearerTokenBeforeCallingAuthService() {
        assertThrows(ResponseStatusException.class, () -> client.suspend(42L, null));
        server.verify();
    }
}

package com.cityapp.user.service;

import com.cityapp.user.dto.UserResponse;
import com.cityapp.user.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Delegates account-status mutations to the service that owns credentials
 * and refresh-session lifecycle. The caller's signed bearer token is forwarded
 * so auth-service independently enforces SUPER_ADMIN authorization.
 */
@Component
public class AuthAccountStatusClient {

    private final RestClient restClient;

    public AuthAccountStatusClient(
            RestClient.Builder builder,
            @Value("${AUTH_SERVICE_URL:http://localhost:8081}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public UserResponse suspend(Long userId, String authorizationHeader) {
        return updateStatus(userId, "suspend", authorizationHeader);
    }

    public UserResponse reinstate(Long userId, String authorizationHeader) {
        return updateStatus(userId, "reinstate", authorizationHeader);
    }

    private UserResponse updateStatus(
            Long userId, String operation, String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")
                || authorizationHeader.length() <= "Bearer ".length()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "A bearer token is required");
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/api/v1/auth/admin/users/{userId}/{operation}", userId, operation)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("success").asBoolean()
                    || !response.path("data").isObject()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Auth service returned an invalid response");
            }

            return mapUserResponse(response.path("data"));
        } catch (RestClientResponseException ex) {
            // Preserve auth-service 4xx/5xx status codes without exposing its body.
            throw new ResponseStatusException(
                    ex.getStatusCode(), "Auth service rejected the account-status operation");
        } catch (ResourceAccessException ex) {
            // Fail closed: never fall back to writing the main service's local user row.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Auth service is unavailable");
        }
    }

    private UserResponse mapUserResponse(JsonNode data) {
        try {
            String roleValue = data.path("role").asText(null);
            String createdAtValue = data.path("createdAt").asText(null);

            return UserResponse.builder()
                    .id(data.path("id").isNumber() ? data.path("id").longValue() : null)
                    .name(data.path("name").asText(null))
                    .email(data.path("email").asText(null))
                    .phone(data.path("phone").asText(null))
                    .role(roleValue == null ? null : Role.valueOf(roleValue))
                    .enabled(data.path("enabled").asBoolean())
                    .profileImageUrl(data.path("profileImageUrl").asText(null))
                    .createdAt(createdAtValue == null ? null : Instant.parse(createdAtValue))
                    .build();
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Auth service returned an invalid user payload");
        }
    }
}

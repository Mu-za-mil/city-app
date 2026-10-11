package com.cityapp.security.service;

import com.cityapp.security.principal.IdentifiedUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtServiceIdentityClaimTest {

    private static final String EMAIL = "identity.claim@example.com";
    private static final String SECRET = "test-secret-that-is-at-least-thirty-two-bytes-long";

    private StringRedisTemplate redisTemplate;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        jwtService = new JwtService(redisTemplate);
        ReflectionTestUtils.setField(jwtService, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpirationMs", 900_000L);
    }

    @Test
    void includesStableUserIdClaimAndRejectsDifferentLocalIdentity() {
        IdentifiedUserDetails user = new TestIdentity("42", EMAIL);

        String token = jwtService.generateAccessToken(user);

        assertEquals("42", jwtService.extractUserId(token));
        assertTrue(jwtService.isValid(token, user));
        assertFalse(
                jwtService.isValid(token, new TestIdentity("43", EMAIL)),
                "A token's stable user ID must match the loaded account");
    }

    @Test
    void rejectsTokenGenerationBeforeUserHasPersistedId() {
        IdentifiedUserDetails user = new TestIdentity(null, EMAIL);

        assertThrows(IllegalArgumentException.class,
                () -> jwtService.generateAccessToken(user));
    }

    @Test
    void continuesToAcceptLegacyTokensWithoutStableIdClaim() {
        UserDetailsForLegacy user = new UserDetailsForLegacy(EMAIL);

        String token = jwtService.generateAccessToken(user);

        assertNull(jwtService.extractUserId(token));
        assertTrue(jwtService.isValid(token, user));
    }

    private record UserDetailsForLegacy(String username)
            implements org.springframework.security.core.userdetails.UserDetails {
        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }

        @Override
        public String getPassword() {
            return "not-used";
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    private record TestIdentity(String userId, String username)
            implements IdentifiedUserDetails {
        @Override
        public String getUserId() {
            return userId;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }

        @Override
        public String getPassword() {
            return "not-used";
        }

        @Override
        public String getUsername() {
            return username;
        }

        @Override
        public boolean isAccountNonExpired() {
            return true;
        }

        @Override
        public boolean isAccountNonLocked() {
            return true;
        }

        @Override
        public boolean isCredentialsNonExpired() {
            return true;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}

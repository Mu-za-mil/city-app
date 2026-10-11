package com.cityapp.security.filter;

import com.cityapp.security.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterAccountStatusTest {

    private static final String EMAIL = "account.status@example.com";
    private static final String TOKEN = "valid-token";

    private final JwtService jwtService = mock(JwtService.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, userDetailsService);

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        when(jwtService.extractUsername(TOKEN)).thenReturn(EMAIL);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void establishesAuthenticationForEligibleAccount() throws Exception {
        UserDetails user = user(true, true, true, true);
        arrangeValidToken(user);

        runFilter();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertNotNull(authentication.getPrincipal());
    }

    @Test
    void rejectsDisabledAccountEvenWhenTokenIsValid() throws Exception {
        assertAccountRejected(user(false, true, true, true));
    }

    @Test
    void rejectsLockedAccountEvenWhenTokenIsValid() throws Exception {
        assertAccountRejected(user(true, false, true, true));
    }

    @Test
    void rejectsExpiredAccountEvenWhenTokenIsValid() throws Exception {
        assertAccountRejected(user(true, true, false, true));
    }

    @Test
    void rejectsExpiredCredentialsEvenWhenTokenIsValid() throws Exception {
        assertAccountRejected(user(true, true, true, false));
    }

    private void assertAccountRejected(UserDetails user) throws Exception {
        arrangeValidToken(user);

        runFilter();

        assertNull(
                SecurityContextHolder.getContext().getAuthentication(),
                "An ineligible account must not be authenticated by the JWT filter");
    }

    private void arrangeValidToken(UserDetails user) {
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(user);
        when(jwtService.isValid(TOKEN, user)).thenReturn(true);
    }

    private UserDetails user(
            boolean enabled,
            boolean accountNonLocked,
            boolean accountNonExpired,
            boolean credentialsNonExpired) {
        return User.withUsername(EMAIL)
                .password("not-used")
                .authorities("ROLE_USER")
                .disabled(!enabled)
                .accountLocked(!accountNonLocked)
                .accountExpired(!accountNonExpired)
                .credentialsExpired(!credentialsNonExpired)
                .build();
    }

    private void runFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}

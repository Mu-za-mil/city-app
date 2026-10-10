package com.cityapp.security.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cityapp.security.service.JwtService;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class JwtAuthFilterLoggingTest {

    private static final String EMAIL = "private.user@example.com";
    private final JwtService jwtService = mock(JwtService.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, userDetailsService);
    private final Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        SecurityContextHolder.clearContext();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(appender);
        appender.stop();
        SecurityContextHolder.clearContext();
    }

    @Test
    void doesNotLogUserIdentifierWhenAuthenticationSucceeds() throws Exception {
        UserDetails user = User.withUsername(EMAIL)
                .password("not-used")
                .authorities("ROLE_USER")
                .build();
        when(jwtService.extractUsername("valid-token")).thenReturn(EMAIL);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(user);
        when(jwtService.isValid("valid-token", user)).thenReturn(true);

        filter.doFilter(request("valid-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(messages().contains(EMAIL), "Authentication logs must not contain the user's email");
    }

    @Test
    void doesNotLogJwtParserExceptionMessage() throws Exception {
        String parserDetail = "untrusted-parser-detail";
        when(jwtService.extractUsername("invalid-token")).thenThrow(new JwtException(parserDetail));

        filter.doFilter(request("invalid-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(messages().contains(parserDetail), "JWT parser details must not be copied to logs");
    }

    @Test
    void doesNotLogUsernameWhenUserLookupFails() throws Exception {
        when(jwtService.extractUsername("valid-token")).thenReturn(EMAIL);
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("not found: " + EMAIL));

        filter.doFilter(request("valid-token"), new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(messages().contains(EMAIL), "User lookup logs must not contain the user's email");
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private String messages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}

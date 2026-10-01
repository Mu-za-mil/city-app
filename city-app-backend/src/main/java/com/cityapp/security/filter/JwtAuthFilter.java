package com.cityapp.security.filter;

import com.cityapp.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT Authentication Filter — runs on EVERY HTTP request.
 *
 * SECURITY MODEL:
 *   The gateway validates JWTs first, but the monolith validates the JWT too.
 *   This service never treats X-Gateway-Request, X-User-Email, X-User-Id or
 *   X-User-Role as authentication credentials.
 *
 *   Why this matters:
 *   A header is client-controlled unless the trust boundary is independently
 *   enforced. If the monolith is accidentally exposed directly, forged
 *   identity headers must not create an authenticated SecurityContext.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No token provided.
            // Public endpoints continue through the chain.
            // Protected endpoints are rejected by Spring Security's
            // .anyRequest().authenticated() rule.
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7);

        String username;
        try {
            username = jwtService.extractUsername(token);
        } catch (Exception e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (username != null &&
                SecurityContextHolder.getContext().getAuthentication() == null) {

            // Load the actual User from the database so account state and
            // current authorities are checked before authentication is set.
            var userDetails = userDetailsService.loadUserByUsername(username);

            if (jwtService.isValid(token, userDetails)) {
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("JWT authentication set for user: {}", username);
            }
        }

        filterChain.doFilter(request, response);
    }
}

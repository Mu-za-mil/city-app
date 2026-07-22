package com.cityapp.auth.filter;

import com.cityapp.auth.service.JwtService;
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
 * EXTENDS OncePerRequestFilter:
 *   WHY "Once": Spring's filter chain can call a filter multiple times
 *   in forward/include scenarios (e.g., error forwarding).
 *   OncePerRequestFilter guarantees: exactly one execution per HTTP request.
 *   Without this: authentication runs twice for forwarded requests.
 *   Result: double logging, potential double-loading from DB.
 *
 * FILTER ORDER:
 *   This filter runs BEFORE Spring Security's default authentication.
 *   We add it to the filter chain in SecurityConfig with:
 *   .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
 *
 * EXECUTION FLOW:
 *   Request arrives
 *       ↓
 *   JwtAuthFilter runs
 *       ↓ (if no Authorization header or invalid token)
 *   Skip (continue filter chain without setting authentication)
 *   Spring Security: no authentication set → 401 for protected endpoints
 *       ↓ (if valid token)
 *   Load User from DB
 *   Set authentication in SecurityContext
 *   Continue filter chain
 *   Spring Security: authentication set → allow access
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        // Authorization header format: "Bearer eyJhbGci..."
        // "Bearer " is 7 characters.

        // Step 1: Check if Authorization header exists and is in Bearer format
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No token provided.
            // For PUBLIC endpoints: filter chain continues, endpoint works.
            // For PROTECTED endpoints: Spring Security rejects with 401.
            // We don't reject here — let Spring Security do it.
            filterChain.doFilter(request, response);
            return;
        }

        // Step 2: Extract token (remove "Bearer " prefix)
        final String token = authHeader.substring(7);

        // Step 3: Extract username from token
        String username;
        try {
            username = jwtService.extractUsername(token);
        } catch (Exception e) {
            // Token is malformed or has invalid signature.
            // Log at DEBUG not WARN: attackers constantly send garbage tokens.
            // WARN level would flood the alerting with false positives.
            log.debug("Invalid JWT token: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // Step 4: Process only if username extracted AND SecurityContext is empty
        // WHY CHECK SecurityContext is empty:
        //   SecurityContext may already be set from a previous filter.
        //   Don't overwrite existing valid authentication.
        //   This also prevents processing the token twice.
        if (username != null &&
                SecurityContextHolder.getContext().getAuthentication() == null) {

            // Step 5: Load the actual User from the database
            // WHY LOAD FROM DB (not just trust the token):
            //   Token has: email, expiry.
            //   DB has: enabled status, accountNonLocked, current role.
            //
            //   Scenario: admin suspends user X.
            //   User X's token is still valid (hasn't expired).
            //   Without DB check: user X can still make requests.
            //   With DB check: user.isEnabled() returns false → request rejected.
            //
            //   Cost: 1 DB query per authenticated request.
            //   With HikariCP connection pool: typically <1ms.
            //   This cost is worth the security guarantee.
            var userDetails = userDetailsService.loadUserByUsername(username);

            // Step 6: Validate the token
            if (jwtService.isValid(token, userDetails)) {

                // Step 7: Create authentication token and set it in SecurityContext
                //
                // WHY UsernamePasswordAuthenticationToken:
                //   This is Spring Security's standard way to represent
                //   an authenticated user in the SecurityContext.
                //   Constructor params: (principal, credentials, authorities)
                //   principal = the User object (@AuthenticationPrincipal will return this)
                //   credentials = null (we don't need the password after auth)
                //   authorities = user's roles (["ROLE_SELLER"])
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                // Add request metadata (IP address, session ID) to authentication
                // Used by Spring Security audit logging
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                // Set the authentication in the SecurityContext
                // This is how Spring Security knows: "request is authenticated as user X"
                // @AuthenticationPrincipal reads from here
                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("JWT authentication set for user: {}", username);
            }
        }

        // Step 8: Continue the filter chain
        // The request proceeds to the controller.
        filterChain.doFilter(request, response);
    }
}

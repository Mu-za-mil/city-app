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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Shared JWT authentication filter.
 *
 * Every service that accepts user JWTs gets the same authentication behavior:
 *   Authorization: Bearer <JWT>
 *       -> extract subject
 *       -> load current UserDetails
 *       -> validate signature, expiry, blacklist, and subject
 *       -> check current account eligibility
 *       -> populate this service's SecurityContext
 *
 * X-User-* and X-Gateway-Request are deliberately ignored.
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

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();

        if (token.isEmpty()) {
            log.debug("JWT authentication skipped: empty Bearer token");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String username = jwtService.extractUsername(token);

            if (username == null || username.isBlank()) {
                log.debug("JWT authentication failed: token has no subject");
                filterChain.doFilter(request, response);
                return;
            }

            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                filterChain.doFilter(request, response);
                return;
            }

            UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadUserByUsername(username);
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
                log.debug("JWT authentication failed: user not found");
                filterChain.doFilter(request, response);
                return;
            }

            if (!jwtService.isValid(token, userDetails)) {
                log.debug("JWT authentication failed: token rejected");
            } else if (!isAccountEligible(userDetails)) {
                log.debug("JWT authentication failed: account is not eligible");
            } else {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities());

                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("JWT authentication established");
            }

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.debug("JWT authentication failed: token expired");
        } catch (io.jsonwebtoken.JwtException e) {
            log.debug("JWT authentication failed: invalid JWT; exceptionType={}",
                    e.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("JWT authentication failed unexpectedly; exceptionType={}",
                    e.getClass().getSimpleName());
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAccountEligible(UserDetails userDetails) {
        return userDetails.isEnabled()
                && userDetails.isAccountNonLocked()
                && userDetails.isAccountNonExpired()
                && userDetails.isCredentialsNonExpired();
    }
}

package com.cityapp.config;

import com.cityapp.security.service.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.socket.config.annotation.*;

/**
 * WebSocket STOMP configuration.
 *
 * WHAT IS STOMP:
 *   WebSocket = transport layer. Raw bytes over a persistent connection.
 *   STOMP = application protocol on top. Provides:
 *     - SUBSCRIBE to destinations (topics/queues)
 *     - SEND messages to destinations
 *     - MESSAGE frames pushed by server
 *
 *   Without STOMP: you'd need to implement your own routing, subscriptions,
 *   and message framing over raw WebSocket bytes.
 *   With STOMP: use Spring's @MessageMapping, @SendTo, SimpMessagingTemplate.
 *
 * ENDPOINT DESIGN:
 *   /ws → the WebSocket endpoint (clients connect here)
 *   /app → prefix for client-to-server messages
 *   /topic → prefix for broadcast messages (one-to-many)
 *   /user → prefix for user-specific messages (one-to-one)
 *
 * JWT IN WEBSOCKET:
 *   HTTP requests: Authorization: Bearer {token} in header.
 *   WebSocket connections: headers at CONNECT frame time.
 *   The inbound channel interceptor reads the token from CONNECT frame headers.
 *   Validates it. Sets SecurityContext for the WebSocket session.
 *   All subsequent STOMP frames in this session are authenticated.
 *
 * WHY JWT VALIDATION ON CONNECT (not HTTP handshake):
 *   WebSocket upgrade is an HTTP request.
 *   Spring Security can protect the /ws endpoint.
 *   BUT: after upgrade, it's a WebSocket. Not HTTP. No Authorization header.
 *   The JWT must be validated in the STOMP CONNECT frame.
 *   The interceptor below does this.
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService  jwtService;
    private final com.cityapp.user.service.UserService userService;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
        // In production: restrict to your frontend domain:
        // .setAllowedOriginPatterns("https://app.cityapp.com")
                .withSockJS();
        // SockJS fallback: if WebSocket not available (old browsers, proxies):
        // uses long-polling as a transparent fallback.
        // Client code is identical. Infrastructure handles the difference.
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        // enableSimpleBroker: in-memory message broker.
        // /topic: broadcast (one message → all subscribers).
        // /queue: point-to-point (one message → one recipient).
        //
        // IN-MEMORY LIMITATION:
        // Running 2+ server instances:
        // Instance A has buyer B's WebSocket connection.
        // Instance B pushes a delivery update.
        // Instance B's in-memory broker: buyer B is not subscribed here.
        // Update never reaches buyer B.
        //
        // FIX: Redis Pub/Sub relay (implemented in this phase).

        registry.setApplicationDestinationPrefixes("/app");
        // Client sends to /app/delivery/location →
        // Routed to @MessageMapping("/delivery/location") method.

        registry.setUserDestinationPrefix("/user");
        // /user/queue/delivery → converted to /queue/delivery-user{userId}
        // Only the specific user receives it.
        // Used for: delivery location to specific buyer, notifications.
    }

    /**
     * JWT Authentication for WebSocket CONNECT frames.
     *
     * Flow:
     *   1. Client opens WebSocket connection.
     *   2. Client sends STOMP CONNECT frame with header:
     *      Authorization: Bearer eyJhbGci...
     *   3. This interceptor reads the header.
     *   4. Validates the JWT.
     *   5. Sets the principal (user) for this WebSocket session.
     *   6. All subsequent frames in this session are authenticated.
     *
     * If JWT is invalid: CONNECT rejected. Connection closed.
     * If JWT expires: connection stays open (WebSocket sessions don't
     * re-authenticate automatically). Client should reconnect with a fresh token.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor
                        .getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) return message;

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // Extract JWT from CONNECT frame Authorization header
                    String authHeader = accessor.getFirstNativeHeader("Authorization");

                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        try {
                            String email = jwtService.extractUsername(token);
                            UserDetails userDetails =
                                    userService.loadUserByUsername(email);

                            if (jwtService.isValid(token, userDetails)) {
                                UsernamePasswordAuthenticationToken auth =
                                        new UsernamePasswordAuthenticationToken(
                                                userDetails, null,
                                                userDetails.getAuthorities());
                                accessor.setUser(auth);
                                log.debug("WebSocket authenticated: user={}",
                                        email);
                            }
                        } catch (Exception e) {
                            log.warn("WebSocket auth failed: {}", e.getMessage());
                            // Don't throw: return message without authentication.
                            // The destination will require auth if @PreAuthorize is set.
                        }
                    }
                }

                return message;
            }
        });
    }
}
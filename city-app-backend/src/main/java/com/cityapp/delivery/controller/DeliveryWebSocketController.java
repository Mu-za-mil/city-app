package com.cityapp.delivery.controller;

import com.cityapp.delivery.dto.UpdateLocationRequest;
import com.cityapp.delivery.service.DeliveryService;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

/**
 * Handles STOMP WebSocket messages from delivery partners.
 *
 * Partner sends: STOMP SEND to /app/delivery/{orderId}/location
 * Server routes to: @MessageMapping("/delivery/{orderId}/location")
 * Server pushes to buyer: via SimpMessagingTemplate (in DeliveryService)
 *
 * WHY A WEBSOCKET CONTROLLER IN ADDITION TO THE REST CONTROLLER:
 *   Partners CAN send location via REST (POST /api/v1/delivery/{orderId}/location).
 *   Partners CAN ALSO send via WebSocket STOMP.
 *   WebSocket: lower overhead (no HTTP headers, persistent connection).
 *   REST: works when WebSocket is unavailable (bad network, proxies).
 *   Both paths call the same DeliveryService.updateLocation().
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class DeliveryWebSocketController {

    private final DeliveryService deliveryService;

    @MessageMapping("/delivery/{orderId}/location")
    public void updateLocationViaWebSocket(
            @DestinationVariable Long orderId,
            @Payload UpdateLocationRequest req,
            @AuthenticationPrincipal User partner) {

        if (partner == null) {
            log.warn("Unauthenticated WebSocket location update for orderId={}", orderId);
            return;
        }

        log.debug("WS location update: partnerId={} orderId={} lat={} lng={}",
                partner.getId(), orderId, req.getLatitude(), req.getLongitude());

        deliveryService.updateLocation(partner.getId(), orderId, req);
    }
}

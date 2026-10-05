package com.cityapp.delivery.service;

import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.exception.AppException;
import com.cityapp.config.RedisWebSocketRelay;
import com.cityapp.delivery.entity.DeliveryAssignment;
import com.cityapp.delivery.entity.DeliveryPartner;
import com.cityapp.delivery.repository.DeliveryAssignmentRepository;
import com.cityapp.delivery.repository.DeliveryPartnerRepository;
import com.cityapp.order.entity.Order;
import com.cityapp.store.entity.Store;
import com.cityapp.user.entity.Role;
import com.cityapp.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock
    private DeliveryPartnerRepository partnerRepository;

    @Mock
    private DeliveryAssignmentRepository assignmentRepository;

    @Mock
    private com.cityapp.order.repository.OrderRepository orderRepository;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private RedisWebSocketRelay webSocketRelay;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private DeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        deliveryService = new DeliveryService(
                partnerRepository,
                assignmentRepository,
                orderRepository,
                eventPublisher,
                webSocketRelay,
                redisTemplate);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getCurrentLocation_shouldRejectUserWhoHasNoRelationshipToOrder() {
        User unrelatedUser = user(99L, Role.USER);

        when(assignmentRepository.findAuthorizedByOrderId(100L, 99L))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> deliveryService.getCurrentLocation(100L, unrelatedUser));

        assertEquals(404, exception.getStatus().value());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void getCurrentLocation_shouldAllowBuyer() {
        User buyer = user(1L, Role.USER);
        DeliveryAssignment assignment = assignment(100L, buyer, 20L, 2L);

        when(assignmentRepository.findAuthorizedByOrderId(100L, 1L))
                .thenReturn(Optional.of(assignment));
        when(valueOperations.get(anyString())).thenReturn("13.0400,80.2300");

        var location = deliveryService.getCurrentLocation(100L, buyer);

        assertEquals(13.0400, location.getLatitude());
        assertEquals(80.2300, location.getLongitude());
        verify(assignmentRepository).findAuthorizedByOrderId(100L, 1L);
    }

    @Test
    void getCurrentLocation_shouldAllowAssignedDeliveryPartner() {
        User partnerUser = user(2L, Role.DELIVERY_PARTNER);
        DeliveryAssignment assignment = assignment(100L, user(1L, Role.USER), 20L, 2L);

        when(assignmentRepository.findAuthorizedByOrderId(100L, 2L))
                .thenReturn(Optional.of(assignment));
        when(valueOperations.get(anyString())).thenReturn("13.0400,80.2300");

        var location = deliveryService.getCurrentLocation(100L, partnerUser);

        assertEquals(13.0400, location.getLatitude());
        assertEquals(80.2300, location.getLongitude());
        verify(assignmentRepository).findAuthorizedByOrderId(100L, 2L);
    }

    @Test
    void getCurrentLocation_shouldAllowStoreOwner() {
        User seller = user(3L, Role.SELLER);
        DeliveryAssignment assignment = assignment(100L, user(1L, Role.USER), 20L, 2L);
        assignment.getOrder().getStore().setOwner(seller);

        when(assignmentRepository.findAuthorizedByOrderId(100L, 3L))
                .thenReturn(Optional.of(assignment));
        when(valueOperations.get(anyString())).thenReturn("13.0400,80.2300");

        var location = deliveryService.getCurrentLocation(100L, seller);

        assertEquals(13.0400, location.getLatitude());
        assertEquals(80.2300, location.getLongitude());
        verify(assignmentRepository).findAuthorizedByOrderId(100L, 3L);
    }

    @Test
    void getCurrentLocation_shouldAllowSuperAdmin() {
        User admin = user(4L, Role.SUPER_ADMIN);
        DeliveryAssignment assignment = assignment(100L, user(1L, Role.USER), 20L, 2L);

        when(assignmentRepository.findByOrderId(100L))
                .thenReturn(Optional.of(assignment));
        when(valueOperations.get(anyString())).thenReturn("13.0400,80.2300");

        var location = deliveryService.getCurrentLocation(100L, admin);

        assertEquals(13.0400, location.getLatitude());
        assertEquals(80.2300, location.getLongitude());
        verify(assignmentRepository).findByOrderId(100L);
        verify(assignmentRepository, never()).findAuthorizedByOrderId(anyLong(), anyLong());
    }

    private static User user(Long id, Role role) {
        return User.builder()
                .id(id)
                .name("User " + id)
                .email("user" + id + "@example.com")
                .passwordHash("hash")
                .role(role)
                .build();
    }

    private static DeliveryAssignment assignment(
            Long orderId, User buyer, Long partnerId, Long partnerUserId) {

        User partnerUser = user(partnerUserId, Role.DELIVERY_PARTNER);
        DeliveryPartner partner = DeliveryPartner.builder()
                .id(partnerId)
                .user(partnerUser)
                .build();

        Store store = Store.builder()
                .id(10L)
                .owner(user(3L, Role.SELLER))
                .name("Test Store")
                .build();

        Order order = Order.builder()
                .id(orderId)
                .user(buyer)
                .store(store)
                .build();

        return DeliveryAssignment.builder()
                .id(50L)
                .order(order)
                .partner(partner)
                .status(DeliveryAssignment.AssignmentStatus.ASSIGNED)
                .build();
    }
}

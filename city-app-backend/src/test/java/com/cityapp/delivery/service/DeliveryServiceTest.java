package com.cityapp.delivery.service;

import com.cityapp.common.event.EventPublisher;
import com.cityapp.common.exception.AppException;
import com.cityapp.config.RedisWebSocketRelay;
import com.cityapp.delivery.dto.DeliveryAssignmentResponse;
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
    private DeliveryAssignmentResponse deliveryAssignmentResponse;

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

    }


    @Test
    void assignPartner_shouldRejectSellerWhoDoesNotOwnOrder() {
        User seller = user(99L, Role.SELLER);

        when(orderRepository.findByIdAndStoreOwnerId(100L, 99L))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> deliveryService.assignPartner(100L, seller));

        assertEquals(404, exception.getStatus().value());
        verify(orderRepository).findByIdAndStoreOwnerId(100L, 99L);
        verify(orderRepository, never()).findById(100L);
        verify(assignmentRepository, never()).findByOrderId(anyLong());
        verify(partnerRepository, never()).findAvailableNearStore(anyDouble(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void assignPartner_shouldAllowSellerWhoOwnsOrder() {
        User seller = user(3L, Role.SELLER);
        Order order = order(100L, user(1L, Role.USER), seller);
        order.setStatus(com.cityapp.order.entity.OrderStatus.CONFIRMED);
        order.getStore().setCoordinates(13.0400, 80.2300);

        DeliveryPartner partner = DeliveryPartner.builder()
                .id(20L)
                .user(user(2L, Role.DELIVERY_PARTNER))
                .build();

        when(orderRepository.findByIdAndStoreOwnerId(100L, 3L))
                .thenReturn(Optional.of(order));
        when(assignmentRepository.findByOrderId(100L))
                .thenReturn(Optional.empty());
        when(partnerRepository.findAvailableNearStore(13.0400, 80.2300, 10000.0, 5))
                .thenReturn(java.util.List.of(partner));
        when(assignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeliveryAssignmentResponse response =
                deliveryService.assignPartner(100L, seller);

        assertEquals(100L, response.getOrderId());
        assertEquals(20L, response.getPartnerId());
        assertEquals(com.cityapp.order.entity.OrderStatus.OUT_FOR_DELIVERY, order.getStatus());
        verify(orderRepository).findByIdAndStoreOwnerId(100L, 3L);
        verify(orderRepository, never()).findById(100L);
        verify(assignmentRepository).save(any(DeliveryAssignment.class));
        verify(orderRepository).save(order);
        verify(eventPublisher).publishDeliveryAssigned(any());
    }

    @Test
    void assignPartner_shouldAllowSuperAdminWithoutStoreOwnershipScope() {
        User admin = user(4L, Role.SUPER_ADMIN);
        Order order = order(100L, user(1L, Role.USER), user(3L, Role.SELLER));
        order.setStatus(com.cityapp.order.entity.OrderStatus.CONFIRMED);
        order.getStore().setCoordinates(13.0400, 80.2300);

        DeliveryPartner partner = DeliveryPartner.builder()
                .id(20L)
                .user(user(2L, Role.DELIVERY_PARTNER))
                .build();

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(assignmentRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(partnerRepository.findAvailableNearStore(13.0400, 80.2300, 10000.0, 5))
                .thenReturn(java.util.List.of(partner));
        when(assignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeliveryAssignmentResponse response =
                deliveryService.assignPartner(100L, admin);

        assertEquals(100L, response.getOrderId());
        assertEquals(20L, response.getPartnerId());
        verify(orderRepository).findById(100L);
        verify(orderRepository, never()).findByIdAndStoreOwnerId(anyLong(), anyLong());
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
        stubRedisLocation("13.0400,80.2300");

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
        stubRedisLocation("13.0400,80.2300");

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
        stubRedisLocation("13.0400,80.2300");

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
        stubRedisLocation("13.0400,80.2300");

        var location = deliveryService.getCurrentLocation(100L, admin);

        assertEquals(13.0400, location.getLatitude());
        assertEquals(80.2300, location.getLongitude());
        verify(assignmentRepository).findByOrderId(100L);
        verify(assignmentRepository, never()).findAuthorizedByOrderId(anyLong(), anyLong());
    }

    private void stubRedisLocation(String location) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(location);
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

    private static Order order(Long orderId, User buyer, User seller) {
        Store store = Store.builder()
                .id(10L)
                .owner(seller)
                .name("Test Store")
                .build();

        return Order.builder()
                .id(orderId)
                .user(buyer)
                .store(store)
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

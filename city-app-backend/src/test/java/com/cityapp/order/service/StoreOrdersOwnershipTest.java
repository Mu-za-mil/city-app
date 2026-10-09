package com.cityapp.order.service;

import com.cityapp.common.exception.AppException;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderStatus;
import com.cityapp.order.mapper.OrderMapper;
import com.cityapp.order.repository.OrderRepository;
import com.cityapp.payment.repository.PaymentRepository;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.store.entity.Store;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.common.event.EventPublisher;
import com.cityapp.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreOrdersOwnershipTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock StoreRepository storeRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock OrderMapper orderMapper;
    @Mock EventPublisher eventPublisher;
    @Mock OutboxService outboxService;

    @InjectMocks OrderService orderService;

    @Test
    void sellerCanListOrdersForTheirOwnStore() {
        Long storeId = 42L;
        Long sellerId = 7L;
        PageRequest pageable = PageRequest.of(0, 20);
        Store ownedStore = mock(Store.class);

        when(storeRepository.findByIdAndOwnerId(storeId, sellerId))
                .thenReturn(Optional.of(ownedStore));
        when(orderRepository.findByStoreIdOrderByCreatedAtDesc(storeId, pageable))
                .thenReturn(Page.empty(pageable));

        orderService.getStoreOrders(storeId, sellerId, null, pageable);

        verify(storeRepository).findByIdAndOwnerId(storeId, sellerId);
        verify(orderRepository).findByStoreIdOrderByCreatedAtDesc(storeId, pageable);
        verify(orderRepository, never())
                .findByStoreIdAndStatusOrderByCreatedAtDesc(anyLong(), any(), any());
    }

    @Test
    void sellerCannotListOrdersForAnotherSellersStore() {
        Long storeId = 42L;
        Long sellerId = 7L;
        PageRequest pageable = PageRequest.of(0, 20);

        when(storeRepository.findByIdAndOwnerId(storeId, sellerId))
                .thenReturn(Optional.empty());

        AppException exception = assertThrows(
                AppException.class,
                () -> orderService.getStoreOrders(storeId, sellerId, null, pageable));

        assertEquals(404, exception.getStatus().value());
        verify(orderRepository, never())
                .findByStoreIdOrderByCreatedAtDesc(anyLong(), any());
        verify(orderRepository, never())
                .findByStoreIdAndStatusOrderByCreatedAtDesc(anyLong(), any(), any());
        verify(orderMapper, never()).toResponse(any(Order.class));
        verify(paymentRepository, never()).findByOrderId(anyLong());
    }

    @Test
    void sellerCannotUseStatusFilterToReadAnotherSellersOrders() {
        Long storeId = 42L;
        Long sellerId = 7L;
        PageRequest pageable = PageRequest.of(0, 20);

        when(storeRepository.findByIdAndOwnerId(storeId, sellerId))
                .thenReturn(Optional.empty());

        assertThrows(AppException.class,
                () -> orderService.getStoreOrders(
                        storeId, sellerId, OrderStatus.PREPARING, pageable));

        verify(orderRepository, never())
                .findByStoreIdAndStatusOrderByCreatedAtDesc(anyLong(), any(), any());
    }
}

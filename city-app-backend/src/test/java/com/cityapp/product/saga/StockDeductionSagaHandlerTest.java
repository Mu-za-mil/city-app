package com.cityapp.product.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.InventoryLowEvent;
import com.cityapp.common.event.PlaceOrderCommand;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.common.event.StockRestoredEvent;
import com.cityapp.outbox.service.OutboxService;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.Product;
import com.cityapp.store.entity.Store;
import com.cityapp.user.entity.User;
import com.cityapp.product.entity.SagaStockDeduction;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.SagaStockDeductionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockDeductionSagaHandlerTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock SagaStockDeductionRepository deductionRepository;
    @Mock OutboxService outboxService;
    @Mock com.cityapp.common.event.EventPublisher eventPublisher;

    private StockDeductionSagaHandler handler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new StockDeductionSagaHandler(
                inventoryRepository,
                deductionRepository,
                outboxService,
                eventPublisher,
                objectMapper);
    }

    @Test
    void deductStock_shouldEnqueueLowStockEventInSameTransaction() throws Exception {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(7L);
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(3L);
        when(store.getOwner()).thenReturn(owner);
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(10L);
        when(product.getName()).thenReturn("Coffee");
        when(product.getStore()).thenReturn(store);

        Inventory inventory = mock(Inventory.class);
        when(inventory.getProduct()).thenReturn(product);
        when(inventory.getQuantity()).thenReturn(5);
        when(inventory.getLowStockThreshold()).thenReturn(5);

        when(inventoryRepository.findByProductIdForUpdate(10L))
                .thenReturn(Optional.of(inventory));
        when(deductionRepository.findBySagaIdForUpdate("saga-low-stock"))
                .thenReturn(Optional.empty(), Optional.empty());

        handler.onDeductStock(PlaceOrderCommand.builder()
                .sagaId("saga-low-stock")
                .orderId(20L)
                .items(List.of(PlaceOrderCommand.OrderItemSpec.builder()
                        .productId(10L).quantity(1).build()))
                .build());

        verify(inventory).setQuantity(4);
        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_INVENTORY_LOW),
                eq("10"),
                any(InventoryLowEvent.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void restoreStock_shouldRestoreAndMarkDeductionRestored() throws Exception {
        PlaceOrderCommand.OrderItemSpec item =
                PlaceOrderCommand.OrderItemSpec.builder()
                        .productId(10L).quantity(3).build();

        SagaStockDeduction deduction = SagaStockDeduction.builder()
                .id(1L).sagaId("saga-1").orderId(20L)
                .itemsJson(objectMapper.writeValueAsString(List.of(item)))
                .status(SagaStockDeduction.Status.DEDUCTED)
                .build();

        Inventory inventory = Inventory.builder().quantity(5).build();

        when(deductionRepository.findBySagaIdForUpdate("saga-1"))
                .thenReturn(Optional.of(deduction));
        when(inventoryRepository.findByProductIdForUpdate(10L))
                .thenReturn(Optional.of(inventory));

        handler.onRestoreStock(RestoreStockCommand.builder()
                .sagaId("saga-1").orderId(20L).build());

        assertEquals(8, inventory.getQuantity());
        assertEquals(SagaStockDeduction.Status.RESTORED, deduction.getStatus());
        verify(deductionRepository).save(deduction);
        verify(inventoryRepository).save(inventory);
        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_STOCK_RESTORED),
                eq("20"),
                any(StockRestoredEvent.class));
    }

    @Test
    void restoreStock_shouldNotRestoreTwice() {
        SagaStockDeduction deduction = SagaStockDeduction.builder()
                .id(1L).sagaId("saga-1").orderId(20L)
                .status(SagaStockDeduction.Status.RESTORED)
                .build();

        when(deductionRepository.findBySagaIdForUpdate("saga-1"))
                .thenReturn(Optional.of(deduction));

        handler.onRestoreStock(RestoreStockCommand.builder()
                .sagaId("saga-1").orderId(20L).build());

        verifyNoInteractions(inventoryRepository);
        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_STOCK_RESTORED),
                eq("20"),
                any(StockRestoredEvent.class));
    }
    @Test
    void deductStock_shouldTreatExistingDeductionAsDuplicateWithoutChangingInventory() {
        SagaStockDeduction deduction = SagaStockDeduction.builder()
                .id(1L).sagaId("saga-1").orderId(20L)
                .status(SagaStockDeduction.Status.DEDUCTED)
                .build();

        when(deductionRepository.findBySagaIdForUpdate("saga-1"))
                .thenReturn(Optional.of(deduction));

        handler.onDeductStock(PlaceOrderCommand.builder()
                .sagaId("saga-1")
                .orderId(20L)
                .items(List.of(PlaceOrderCommand.OrderItemSpec.builder()
                        .productId(10L).quantity(3).build()))
                .build());

        verifyNoInteractions(inventoryRepository);
        verify(outboxService).enqueue(
                eq(AppConstants.TOPIC_STOCK_DEDUCTED),
                eq("20"),
                any(com.cityapp.common.event.StockDeductedEvent.class));
    }

    @Test
    void deductStock_shouldNotRedeductAfterSagaWasCompensated() {
        SagaStockDeduction deduction = SagaStockDeduction.builder()
                .id(1L).sagaId("saga-1").orderId(20L)
                .status(SagaStockDeduction.Status.RESTORED)
                .build();

        when(deductionRepository.findBySagaIdForUpdate("saga-1"))
                .thenReturn(Optional.of(deduction));

        handler.onDeductStock(PlaceOrderCommand.builder()
                .sagaId("saga-1")
                .orderId(20L)
                .items(List.of(PlaceOrderCommand.OrderItemSpec.builder()
                        .productId(10L).quantity(3).build()))
                .build());

        verifyNoInteractions(inventoryRepository);
        verifyNoInteractions(outboxService);
    }


    @Test
    void deductStock_shouldAcquireInventoryLocksInProductIdOrder() {
        Inventory product10 = mock(Inventory.class);
        Inventory product20 = mock(Inventory.class);
        when(product10.getQuantity()).thenReturn(100);
        when(product20.getQuantity()).thenReturn(100);

        SagaStockDeduction existing = SagaStockDeduction.builder()
                .id(1L).sagaId("saga-1").orderId(20L)
                .status(SagaStockDeduction.Status.DEDUCTED)
                .build();

        when(inventoryRepository.findByProductIdForUpdate(10L)).thenReturn(Optional.of(product10));
        when(inventoryRepository.findByProductIdForUpdate(20L)).thenReturn(Optional.of(product20));
        when(deductionRepository.findBySagaIdForUpdate("saga-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));

        handler.onDeductStock(PlaceOrderCommand.builder()
                .sagaId("saga-1")
                .orderId(20L)
                .items(List.of(
                        PlaceOrderCommand.OrderItemSpec.builder().productId(20L).quantity(1).build(),
                        PlaceOrderCommand.OrderItemSpec.builder().productId(10L).quantity(1).build()))
                .build());

        InOrder order = inOrder(inventoryRepository);
        order.verify(inventoryRepository).findByProductIdForUpdate(10L);
        order.verify(inventoryRepository).findByProductIdForUpdate(20L);
        verifyNoMoreInteractions(inventoryRepository);
    }

    @Test
    void restoreStock_shouldAcquireInventoryLocksInProductIdOrder() throws Exception {
        PlaceOrderCommand.OrderItemSpec item20 =
                PlaceOrderCommand.OrderItemSpec.builder().productId(20L).quantity(1).build();
        PlaceOrderCommand.OrderItemSpec item10 =
                PlaceOrderCommand.OrderItemSpec.builder().productId(10L).quantity(1).build();

        SagaStockDeduction deduction = SagaStockDeduction.builder()
                .id(1L).sagaId("saga-1").orderId(20L)
                .itemsJson(objectMapper.writeValueAsString(List.of(item20, item10)))
                .status(SagaStockDeduction.Status.DEDUCTED)
                .build();

        Inventory product10 = mock(Inventory.class);
        Inventory product20 = mock(Inventory.class);
        when(inventoryRepository.findByProductIdForUpdate(10L)).thenReturn(Optional.of(product10));
        when(inventoryRepository.findByProductIdForUpdate(20L)).thenReturn(Optional.of(product20));
        when(deductionRepository.findBySagaIdForUpdate("saga-1")).thenReturn(Optional.of(deduction));

        handler.onRestoreStock(RestoreStockCommand.builder()
                .sagaId("saga-1").orderId(20L).build());

        InOrder order = inOrder(inventoryRepository);
        order.verify(inventoryRepository).findByProductIdForUpdate(10L);
        order.verify(inventoryRepository).findByProductIdForUpdate(20L);
    }

}
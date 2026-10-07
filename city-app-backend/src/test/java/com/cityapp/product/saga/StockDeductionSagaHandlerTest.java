package com.cityapp.product.saga;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.PlaceOrderCommand;
import com.cityapp.common.event.RestoreStockCommand;
import com.cityapp.common.event.StockRestoredEvent;
import com.cityapp.outbox.service.OutboxService;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.SagaStockDeduction;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.SagaStockDeductionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

}
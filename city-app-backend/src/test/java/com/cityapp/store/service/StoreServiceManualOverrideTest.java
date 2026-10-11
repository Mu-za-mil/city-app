package com.cityapp.store.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.entity.Store;
import com.cityapp.store.entity.StoreStatus;
import com.cityapp.store.mapper.StoreMapper;
import com.cityapp.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreServiceManualOverrideTest {

    private StoreRepository storeRepository;
    private StoreMapper storeMapper;
    private StoreService storeService;

    @BeforeEach
    void setUp() {
        storeRepository = mock(StoreRepository.class);
        storeMapper = mock(StoreMapper.class);
        storeService = new StoreService(
                storeRepository, mock(CategoryRepository.class), storeMapper);
    }

    @Test
    void schedulerDoesNotReopenStoreAfterSellerManuallyClosesItDuringBusinessHours() {
        Store store = storeWithHours(false, false);

        boolean changed = storeService.reconcileOpenState(store, LocalTime.of(12, 0));

        assertFalse(changed);
        assertFalse(store.isOpen());
        assertEquals(Boolean.FALSE, store.getManualOpenOverride());
    }

    @Test
    void manualCloseOverrideExpiresWhenScheduledClosingTimeArrives() {
        Store store = storeWithHours(false, false);

        boolean changed = storeService.reconcileOpenState(store, LocalTime.of(18, 0));

        assertTrue(changed);
        assertFalse(store.isOpen());
        assertNull(store.getManualOpenOverride());
    }

    @Test
    void automaticScheduleOpensStoreWhenNoManualOverrideExists() {
        Store store = storeWithHours(false, null);

        boolean changed = storeService.reconcileOpenState(store, LocalTime.of(9, 0));

        assertTrue(changed);
        assertTrue(store.isOpen());
        assertNull(store.getManualOpenOverride());
    }

    @Test
    void manualOpenOutsideBusinessHoursIsKeptUntilOpeningTime() {
        Store store = storeWithHours(true, true);

        boolean changedOutsideHours =
                storeService.reconcileOpenState(store, LocalTime.of(20, 0));

        assertFalse(changedOutsideHours);
        assertTrue(store.isOpen());
        assertEquals(Boolean.TRUE, store.getManualOpenOverride());

        boolean changedAtOpening =
                storeService.reconcileOpenState(store, LocalTime.of(9, 0));

        assertTrue(changedAtOpening);
        assertTrue(store.isOpen());
        assertNull(store.getManualOpenOverride());
    }

    @Test
    void sellerTogglePersistsTheExplicitChoice() {
        Store store = storeWithHours(true, null);
        store.setId(7L);
        store.setStatus(StoreStatus.ACTIVE);
        StoreResponse response = mock(StoreResponse.class);

        when(storeRepository.findByIdAndOwnerId(7L, 12L)).thenReturn(Optional.of(store));
        when(storeRepository.save(store)).thenReturn(store);
        when(storeMapper.toResponse(store)).thenReturn(response);

        assertSame(response, storeService.toggleOpenStatus(7L, 12L));

        assertFalse(store.isOpen());
        assertEquals(Boolean.FALSE, store.getManualOpenOverride());
        verify(storeRepository).save(store);
    }

    private Store storeWithHours(boolean open, Boolean manualOverride) {
        return Store.builder()
                .id(1L)
                .status(StoreStatus.ACTIVE)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .open(open)
                .manualOpenOverride(manualOverride)
                .build();
    }
}

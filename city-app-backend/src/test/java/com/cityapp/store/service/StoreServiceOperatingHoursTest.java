package com.cityapp.store.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.store.entity.Store;
import com.cityapp.store.mapper.StoreMapper;
import com.cityapp.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StoreServiceOperatingHoursTest {

    private StoreService storeService;

    @BeforeEach
    void setUp() {
        storeService = new StoreService(
                mock(StoreRepository.class),
                mock(CategoryRepository.class),
                mock(StoreMapper.class));
    }

    @Test
    void shouldBeOpenAtExactOpeningTime() {
        assertTrue(storeService.isWithinHours(storeWithHours("09:00", "18:00"),
                LocalTime.of(9, 0)));
    }

    @Test
    void shouldBeClosedAtExactClosingTime() {
        assertFalse(storeService.isWithinHours(storeWithHours("09:00", "18:00"),
                LocalTime.of(18, 0)));
    }

    @Test
    void shouldHandleOvernightHoursAcrossMidnight() {
        Store store = storeWithHours("22:00", "02:00");

        assertTrue(storeService.isWithinHours(store, LocalTime.of(22, 0)));
        assertTrue(storeService.isWithinHours(store, LocalTime.of(1, 59)));
        assertFalse(storeService.isWithinHours(store, LocalTime.of(2, 0)));
        assertFalse(storeService.isWithinHours(store, LocalTime.NOON));
    }

    @Test
    void shouldTreatEqualOpeningAndClosingTimesAsClosed() {
        Store store = storeWithHours("09:00", "09:00");

        assertFalse(storeService.isWithinHours(store, LocalTime.of(9, 0)));
        assertFalse(storeService.isWithinHours(store, LocalTime.NOON));
    }

    @Test
    void shouldTreatMissingOperatingHoursAsClosed() {
        assertFalse(storeService.isWithinHours(Store.builder().build(), LocalTime.NOON));
    }

    private Store storeWithHours(String openingTime, String closingTime) {
        return Store.builder()
                .openingTime(LocalTime.parse(openingTime))
                .closingTime(LocalTime.parse(closingTime))
                .build();
    }
}

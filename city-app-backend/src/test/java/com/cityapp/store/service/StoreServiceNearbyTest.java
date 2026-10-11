package com.cityapp.store.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.exception.AppException;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.entity.Store;
import com.cityapp.store.mapper.StoreMapper;
import com.cityapp.store.repository.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreServiceNearbyTest {

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
    void convertsKilometresToMetresAndIncludesDistanceInResponse() {
        double latitude = 12.9716;
        double longitude = 77.5946;
        Store store = Store.builder()
                .id(1L)
                .latitude(latitude)
                .longitude(longitude)
                .build();
        StoreResponse response = StoreResponse.builder().id(1L).build();

        when(storeRepository.findNearby(latitude, longitude, 5_000.0, 20))
                .thenReturn(List.of(store));
        when(storeMapper.toResponse(store)).thenReturn(response);

        List<StoreResponse> results = storeService.findNearby(latitude, longitude, 5.0);

        assertEquals(1, results.size());
        assertSame(response, results.get(0));
        assertEquals(0.0, response.getDistanceKm(), 0.000001);
        verify(storeRepository).findNearby(latitude, longitude, 5_000.0, 20);
    }

    @Test
    void clampsNearbyRadiusToConfiguredMaximumBeforeConvertingToMetres() {
        double latitude = 12.0;
        double longitude = 77.0;
        when(storeRepository.findNearby(latitude, longitude, 50_000.0, 20))
                .thenReturn(List.of());

        assertTrue(storeService.findNearby(latitude, longitude, 500.0).isEmpty());

        verify(storeRepository).findNearby(latitude, longitude, 50_000.0, 20);
    }

    @Test
    void rejectsNonPositiveRadiusWithoutQueryingRepository() {
        assertThrows(AppException.class,
                () -> storeService.findNearby(12.0, 77.0, 0.0));

        verifyNoInteractions(storeRepository);
    }
}

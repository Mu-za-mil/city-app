package com.cityapp.store.service;

import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.exception.AppException;
import com.cityapp.store.dto.CreateStoreRequest;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.entity.Store;
import com.cityapp.store.entity.StoreStatus;
import com.cityapp.store.mapper.StoreMapper;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final CategoryRepository categoryRepository;
    private final StoreMapper storeMapper;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public StoreResponse createStore(User seller, CreateStoreRequest req) {

        Store store = storeMapper.toEntity(req);
        store.setOwner(seller);
        store.setStatus(StoreStatus.PENDING_APPROVAL);
        // Always PENDING_APPROVAL on creation.
        // Seller cannot self-approve. Admin must review.

        if (req.getCategoryId() != null) {
            store.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> AppException.notFound(
                            "Category not found: " + req.getCategoryId())));
        }

        if (req.getLatitude() != null && req.getLongitude() != null) {
            store.setCoordinates(req.getLatitude(), req.getLongitude());
            // setCoordinates sets: latitude, longitude, AND the PostGIS location Point
        }

        Store saved = storeRepository.save(store);
        log.info("Store created: id={} name='{}' seller={}",
                saved.getId(), saved.getName(), seller.getEmail());

        return storeMapper.toResponse(saved);
    }

}

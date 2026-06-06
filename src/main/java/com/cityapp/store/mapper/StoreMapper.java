package com.cityapp.store.mapper;

import com.cityapp.store.dto.CreateStoreRequest;
import com.cityapp.store.dto.StoreResponse;
import com.cityapp.store.entity.Store;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface StoreMapper {

    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "categorySlug", source = "category.slug")
    @Mapping(target = "ownerName",    source = "owner.name")
    @Mapping(target = "distanceKm",   ignore = true)
        // distanceKm comes from the Haversine query, not the entity.
        // Set manually in service after mapping.
    StoreResponse toResponse(Store store);

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "owner",        ignore = true)  // set in service
    @Mapping(target = "category",     ignore = true)  // loaded by ID in service
    @Mapping(target = "status",       ignore = true)  // always PENDING_APPROVAL on create
    @Mapping(target = "open",         ignore = true)  // false by default
    @Mapping(target = "avgRating",    ignore = true)
    @Mapping(target = "totalReviews", ignore = true)
    @Mapping(target = "location",     ignore = true)  // set via setCoordinates()
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    Store toEntity(CreateStoreRequest request);
}


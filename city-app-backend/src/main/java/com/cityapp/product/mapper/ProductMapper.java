package com.cityapp.product.mapper;

import com.cityapp.product.dto.CreateProductRequest;
import com.cityapp.product.dto.ProductResponse;
import com.cityapp.product.entity.Product;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(target = "storeId",   source = "store.id")
    @Mapping(target = "storeName", source = "store.name")
    @Mapping(target = "categoryName", source = "category.name")
    @Mapping(target = "stockQuantity", ignore = true) // set in service
    ProductResponse toResponse(Product product);

    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "store",       ignore = true)
    @Mapping(target = "category",    ignore = true)
    @Mapping(target = "active",      ignore = true)
    @Mapping(target = "avgRating",   ignore = true)
    @Mapping(target = "totalReviews",ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    Product toEntity(CreateProductRequest request);
}

package com.cityapp.order.mapper;

import com.cityapp.order.dto.OrderItemResponse;
import com.cityapp.order.dto.OrderResponse;
import com.cityapp.order.entity.Order;
import com.cityapp.order.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(target = "userId",    source = "user.id")
    @Mapping(target = "storeId",   source = "store.id")
    @Mapping(target = "storeName", source = "store.name")
    @Mapping(target = "items",     source = "items")
    OrderResponse toResponse(Order order);

    @Mapping(target = "productId", source = "product.id")
    // productName maps automatically (same name in entity and DTO)
    OrderItemResponse toItemResponse(OrderItem item);
}

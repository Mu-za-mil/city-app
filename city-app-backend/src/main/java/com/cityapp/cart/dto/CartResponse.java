package com.cityapp.cart.dto;

import com.cityapp.cart.model.CartItem;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class CartResponse {
    private Long          storeId;
    private String        storeName;
    private List<CartItem> items;
    private int           totalItems;
    private BigDecimal    totalAmount;
    private Instant       lastUpdated;
}

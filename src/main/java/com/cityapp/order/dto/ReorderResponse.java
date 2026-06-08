package com.cityapp.order.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Returned by the reorder endpoint.
 * Contains the cart that was pre-filled + any unavailable items
 * so the frontend can warn the user before they hit checkout.
 */
@Data @Builder
public class ReorderResponse {
    private Long             storeId;
    private String           storeName;
    private List<ReorderItem> availableItems;
    private List<ReorderItem> unavailableItems;  // out of stock or product deleted
    private BigDecimal        estimatedTotal;
    private boolean           cartPreFilled;     // true if at least one item was added to cart

    @Data @Builder
    public static class ReorderItem {
        private Long       productId;
        private String     productName;
        private Integer    requestedQty;
        private Integer    availableStock;
        private BigDecimal unitPrice;       // current price (may differ from original)
        private BigDecimal originalPrice;   // price when order was placed
        private boolean    priceChanged;
    }
}

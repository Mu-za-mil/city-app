package com.cityapp.cart.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The complete cart for one buyer at one store.
 *
 * WHY ONE CART PER STORE (not one global cart):
 *   Swiggy, Zomato, Blinkit: each cart is tied to one restaurant/store.
 *   Why: delivery is from ONE location. You can't order from
 *   Chennai Fresh Mart AND PriceBee Electronics in one delivery.
 *
 *   Our key: "cart:{userId}:{storeId}"
 *   Buyer 42 at store 10: "cart:42:10"
 *   Buyer 42 at store 15: "cart:42:15"
 *   Two separate Redis keys. Two independent carts.
 *   Buyer can have 5 different store carts simultaneously.
 *
 *   At checkout: buyer selects one store's cart to checkout.
 *   That cart is cleared. Other store carts remain.
 *
 * WHY @NoArgsConstructor:
 *   Same as CartItem: Jackson needs it to deserialise from Redis.
 *
 * WHY isEmpty() helper:
 *   CartService.checkout() calls this to prevent empty cart checkout.
 *   Cleaner than: if (cart.getItemList() == null || cart.getItemList().isEmpty())
 *   Self-documenting: cart.isEmpty() reads like English.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cart implements Serializable {

    private Long        userId;
    private Long        storeId;
    private String      storeName;   // snapshot for display
    private List<CartItem> itemList;

    @Builder.Default
    // WHY Builder.Default: without it, Cart.builder().build().getItemList() = null.
    // Calling itemList.add() on null throws NullPointerException.
    // Builder.Default ensures itemList is never null when using builder.
    private List<CartItem> items = new ArrayList<>();
    private Instant lastUpdated;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }

    public int getTotalItems() {
        if (items == null) return 0;
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public BigDecimal getTotal() {
        if (items == null) return BigDecimal.ZERO;
        return items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Find an existing item for a product in this cart.
     * Returns null if product is not in cart (it's a new item).
     */
    public CartItem findItem(Long productId) {
        if (items == null) return null;
        return items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst()
                .orElse(null);
    }
}

package com.cityapp.cart.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single item in a cart.
 *
 * WHY IMPLEMENTS Serializable:
 *   Redis serialises this object to JSON using Jackson.
 *   In some Redis configurations and edge cases, Serializable
 *   is used as a fallback serialisation mechanism.
 *   Implementing it provides safety without any downside.
 *   It is also a Java convention for objects stored externally.
 *
 * WHY unitPrice IS STORED HERE (Price Snapshot):
 *
 *   SCENARIO WITHOUT PRICE SNAPSHOT:
 *   Buyer adds Ponni Rice 5kg at ₹189 to cart at 10:00 AM.
 *   Seller raises price to ₹250 at 11:00 AM.
 *   Buyer checks out at 12:00 PM.
 *   Cart calculates: current_price × quantity = ₹250 × 2 = ₹500.
 *   Buyer expected: ₹189 × 2 = ₹378.
 *   Buyer is surprised. Feels cheated. Bad review.
 *
 *   WITH PRICE SNAPSHOT:
 *   Buyer adds at ₹189. unitPrice = 189.00 stored in CartItem.
 *   Seller raises price to ₹250.
 *   Buyer checks out. Cart reads: 189.00 × 2 = ₹378 (original price).
 *   Buyer pays what they expected. Happy buyer.
 *
 *   CAVEAT: If price drops after add-to-cart: buyer pays the old higher price.
 *   This is the conventional e-commerce behaviour. The snapshot protects
 *   the buyer from increases, not from missing out on decreases.
 *   At checkout: log a warning if current_price < snapshot_price.
 *   Client can choose to refresh the cart and reprice.
 *
 * WHY @NoArgsConstructor:
 *   Jackson requires a no-args constructor to deserialise JSON.
 *   Without it: when reading from Redis:
 *   "No suitable constructor found for type CartItem"
 *   App crashes. Cart reading fails.
 *   ALWAYS add @NoArgsConstructor to any class that Jackson deserialises.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CartItem implements Serializable {

    private Long       productId;
    private String     productName;    // snapshot of name at add-time
    private Long       storeId;
    private BigDecimal unitPrice;      // SNAPSHOT: price at time of adding
    private Integer    quantity;
    private String     imageUrl;       // first image for cart display
    private Instant    addedAt;

    // Computed helper — never stored directly
    public BigDecimal getSubtotal() {
        if (unitPrice == null || quantity == null) return BigDecimal.ZERO;
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}

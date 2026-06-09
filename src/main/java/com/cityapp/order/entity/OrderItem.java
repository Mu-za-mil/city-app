package com.cityapp.order.entity;

import com.cityapp.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false)
    private String productName;
    /*
     * SNAPSHOT FIELD: stores product name at order time.
     *
     * WHY SNAPSHOT THE NAME:
     *   Seller renames "Ponni Rice 5kg" to "Premium Ponni Raw Rice 5kg".
     *   Historical orders: should show "Ponni Rice 5kg" (original name at order time).
     *   Without snapshot: order history shows the current name.
     *   "I ordered Ponni Rice 5kg. Why does my order show Premium Ponni Raw Rice?"
     *   Confusing. Support tickets. Audit failures.
     *
     *   With snapshot: order history is immutable.
     *   The order is a legal document at the time of purchase.
     *   Its content must never change after creation.
     *
     * SAME PRINCIPLE APPLIES TO PRICE:
     *   unit_price and subtotal are also snapshots.
     *   They come from the CartItem (which already has the price snapshot from add-to-cart time).
     *   Double snapshot: add-to-cart → CartItem.unitPrice → OrderItem.unitPrice.
     *   The price in the order is the price when the buyer added it to cart.
     */

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;   // snapshot

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;    // unit_price * quantity (snapshot)
}

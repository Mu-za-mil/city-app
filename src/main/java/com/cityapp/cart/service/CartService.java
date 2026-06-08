package com.cityapp.cart.service;

import com.cityapp.cart.dto.AddToCartRequest;
import com.cityapp.cart.dto.CartCheckoutRequest;
import com.cityapp.cart.dto.CartResponse;
import com.cityapp.cart.model.Cart;
import com.cityapp.cart.model.CartItem;
import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.common.util.StoreValidator;
import com.cityapp.order.dto.OrderItemRequest;
import com.cityapp.order.dto.OrderResponse;
import com.cityapp.order.dto.PlaceOrderRequest;
import com.cityapp.order.service.OrderService;
import com.cityapp.product.entity.Inventory;
import com.cityapp.product.entity.Product;
import com.cityapp.product.repository.InventoryRepository;
import com.cityapp.product.repository.ProductRepository;
import com.cityapp.store.entity.Store;
import com.cityapp.store.repository.StoreRepository;
import com.cityapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cart Service — manages the shopping cart stored in Redis.
 *
 * REDIS KEY STRUCTURE:
 *   cart:{userId}:{storeId}
 *   Example: cart:42:10
 *
 *   Why include storeId in key:
 *   One buyer can have carts at multiple stores simultaneously.
 *   Cart 42:10 = Ravi's cart at Chennai Fresh Mart (store 10)
 *   Cart 42:15 = Ravi's cart at PriceBee Electronics (store 15)
 *   Independent. Neither affects the other.
 *
 * TTL STRATEGY:
 *   24 hours, reset on every write.
 *   Active buyers: TTL constantly refreshed.
 *   Abandoned carts: expire 24 hours after last interaction.
 *   Zero cleanup job needed. Redis handles it automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductRepository             productRepository;
    private final InventoryRepository           inventoryRepository;
    private final StoreRepository               storeRepository;
    private final OrderService                  orderService;

    private static final long CART_TTL_SECONDS = 86_400L; // 24 hours

    // ── Add Item ──────────────────────────────────────────────────────────────

    /**
     * Add a product to the buyer's cart for a specific store.
     *
     * OPERATING HOURS CHECK: NOT done here.
     * WHY: Buyers should be able to browse and add to cart at any time.
     *      Store might be closed when buyer adds, but open when they checkout.
     *      Example: buyer adds to cart at 8:55 AM. Store opens at 9 AM.
     *      Checkout at 9:05 AM works correctly.
     *      If we checked hours at add-to-cart: buyer gets "store is closed" at 8:55 AM
     *      even though they haven't tried to checkout yet. Bad UX.
     *      Operating hours are checked at CHECKOUT TIME only.
     *
     * STOCK CHECK: Done here.
     * WHY: Prevent adding more than available stock.
     *      Shows buyer accurate "Only 3 left!" messaging.
     *      Note: this is a SOFT check — stock can change between add-to-cart and checkout.
     *      HARD check happens at inventory deduction in the Saga (Phase 10).
     */
    @Transactional(readOnly = true)
    public CartResponse addItem(User buyer, Long storeId, AddToCartRequest req) {

        // 1. Validate store exists and is ACTIVE (not PENDING/SUSPENDED/CLOSED)
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> AppException.notFound(
                        "Store not found: " + storeId));

        StoreValidator.assertActive(store);
        // Note: NOT calling assertAcceptsOrders() — that includes hours check.
        // Hours check is only at checkout.

        // 2. Validate product exists, is active, and belongs to this store
        Product product = productRepository
                .findByIdAndStoreIdAndActiveTrue(req.getProductId(), storeId)
                .orElseThrow(() -> AppException.notFound(
                        "Product not found in this store: " + req.getProductId()));

        // 3. Check stock availability (soft check)
        Inventory inventory = inventoryRepository
                .findByProductId(product.getId())
                .orElseThrow(() -> AppException.notFound(
                        "Inventory not found for product: " + product.getId()));

        if (inventory.getQuantity() < req.getQuantity()) {
            throw AppException.badRequest(
                    "Only " + inventory.getQuantity() +
                            " unit(s) available for '" + product.getName() + "'");
        }

        // 4. Load existing cart (or create new one)
        Cart cart = loadCart(buyer.getId(), storeId);
        if (cart == null) {
            cart = Cart.builder()
                    .userId(buyer.getId())
                    .storeId(storeId)
                    .storeName(store.getName())
                    .items(new ArrayList<>())
                    .build();
        }

        // 5. Update or add item
        CartItem existingItem = cart.findItem(product.getId());

        if (existingItem != null) {
            // Item already in cart: increment quantity
            int newQty = existingItem.getQuantity() + req.getQuantity();

            // Check: total quantity doesn't exceed stock
            if (newQty > inventory.getQuantity()) {
                throw AppException.badRequest(
                        "Cannot add " + req.getQuantity() + " more. " +
                                "Cart already has " + existingItem.getQuantity() +
                                " and only " + inventory.getQuantity() + " available.");
            }

            existingItem.setQuantity(newQty);
        } else {
            // New item: add with price snapshot
            CartItem newItem = CartItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())     // snapshot name
                    .storeId(storeId)
                    .unitPrice(product.getPrice())      // ← PRICE SNAPSHOT
                    .quantity(req.getQuantity())
                    .imageUrl(product.getImageUrls() != null &&
                            product.getImageUrls().length > 0
                            ? product.getImageUrls()[0] : null)
                    .addedAt(Instant.now())
                    .build();
            cart.getItems().add(newItem);
        }

        cart.setLastUpdated(Instant.now());

        // 6. Save back to Redis with refreshed TTL
        saveCart(buyer.getId(), storeId, cart);

        log.debug("Cart updated: userId={} storeId={} productId={} qty={}",
                buyer.getId(), storeId, req.getProductId(), req.getQuantity());

        return toResponse(cart);
    }


    // ── Clear Cart ────────────────────────────────────────────────────────────

    public void clearCart(Long userId, Long storeId) {
        String key = cartKey(userId, storeId);
        redisTemplate.delete(key);
        log.debug("Cart cleared: userId={} storeId={}", userId, storeId);
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private String cartKey(Long userId, Long storeId) {
        return AppConstants.REDIS_CART_PREFIX + userId + ":" + storeId;
        // Result: "cart:42:10"
    }

    private Cart loadCart(Long userId, Long storeId) {
        try {
            Object raw = redisTemplate.opsForValue().get(cartKey(userId, storeId));
            if (raw == null) return null;

            // With activateDefaultTyping: raw is already a Cart object.
            // Without it: raw would be a LinkedHashMap → ClassCastException.
            return (Cart) raw;

        } catch (ClassCastException e) {
            // This should NEVER happen with correct RedisConfig.
            // If it does: the @class metadata is missing from the stored JSON.
            // Fix: verify activateDefaultTyping is configured in RedisConfig.
            log.error("Cart deserialisation failed for userId={} storeId={}: {}. " +
                            "Check RedisConfig.redisObjectMapper() configuration.",
                    userId, storeId, e.getMessage());
            // Clear the corrupted entry so the user can start fresh
            clearCart(userId, storeId);
            return null;
        }
    }

    private void saveCart(Long userId, Long storeId, Cart cart) {
        redisTemplate.opsForValue().set(
                cartKey(userId, storeId),
                cart,
                CART_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        // TTL is RESET on every write.
        // Active buyer: TTL never reaches 0 (each interaction resets it).
        // Abandoned cart: last write was 24 hours ago → key expires → auto-deleted.
    }

    private CartResponse toResponse(Cart cart) {
        return CartResponse.builder()
                .storeId(cart.getStoreId())
                .storeName(cart.getStoreName())
                .items(cart.getItems())
                .totalItems(cart.getTotalItems())
                .totalAmount(cart.getTotal())
                .lastUpdated(cart.getLastUpdated())
                .build();
    }
}

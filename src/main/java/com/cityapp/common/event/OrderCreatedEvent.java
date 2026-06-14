package com.cityapp.common.event;

import com.cityapp.order.entity.OrderType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Published when an order is confirmed (inventory deducted, status=CONFIRMED).
 *
 * WHY IN cityapp-common (shared):
 *   This event is produced by order-service and consumed by notification-service.
 *   Both services need the same class definition to serialise/deserialise.
 *   If each defines its own version: field name mismatch = silent serialisation failure.
 *   One shared definition in cityapp-common = same class on both sides.
 *
 * WHY NO @Entity, NO @Table:
 *   Events are message payloads, NOT database entities.
 *   They are serialised to JSON and sent over Kafka.
 *   They should be pure POJOs: no JPA annotations, no DB dependencies.
 *   This keeps the event schema clean and independent of DB schema.
 *
 * IMMUTABILITY:
 *   Events represent things that HAPPENED. They cannot be "un-happened."
 *   @Builder makes them easy to construct.
 *   All fields via constructor (AllArgsConstructor for Jackson).
 *   No setters: once published, an event should not be modified.
 *   (@NoArgsConstructor needed for Jackson deserialisation.)
 *
 * EVENT VERSIONING:
 *   Adding fields: add with a default value. Old consumers ignore them.
 *   Removing fields: keep the field, deprecate it, remove in a future version.
 *   Renaming fields: NEVER rename. Add a new field, deprecate the old one.
 *   This is backward-compatible schema evolution.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    private String      eventId;        // UUID, for consumer idempotency
    private Long        orderId;
    private Long        userId;         // buyer
    private Long        sellerId;
    private Long        storeId;
    private String      storeName;
    private OrderType   orderType;
    private BigDecimal  totalAmount;
    private String      deliveryAddress;
    private List<OrderItemInfo> items;
    private Instant     timestamp;

    @Getter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class OrderItemInfo {
        private Long       productId;
        private String     productName;
        private Integer    quantity;
        private BigDecimal unitPrice;
    }
}

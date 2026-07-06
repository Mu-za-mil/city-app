package com.cityapp.order.entity;

/**
 * All possible order statuses and their transitions.
 *
 * STATE MACHINE:
 *
 *   [CREATED] ──inventory deducted──→ [CONFIRMED]
 *       │                                  │
 *       └── inventory unavailable ──→ [CANCELLED]
 *                                          │
 *                                    seller accepts
 *                                          │
 *                                   [PREPARING]
 *                                          │
 *                                    food/items ready
 *                                          │
 *                                   [READY]
 *                                          │ (DELIVERY type)
 *                                   delivery partner assigned
 *                                          │
 *                              [OUT_FOR_DELIVERY]
 *                                          │
 *                                    delivered to buyer
 *                                          │
 *                                   [DELIVERED] (terminal)
 *
 *                                   [READY] (TAKEAWAY type)
 *                                          │
 *                                    buyer collects
 *                                          │
 *                                   [PICKED_UP] (terminal)
 *
 *   Any non-terminal status → admin can set → [CANCELLED]
 *   CANCELLED and DELIVERED and PICKED_UP are terminal (no further transitions).
 *
 * WHY A STATE MACHINE:
 *   Without it: any status can transition to any other status.
 *   "DELIVERED → CREATED" is valid (someone accidentally regresses an order).
 *   Orders appear in active dashboards again. Double-processing. Chaos.
 *
 *   With a state machine: each transition is validated.
 *   Illegal transition → AppException.badRequest() → 400.
 *   Invariant: once DELIVERED, always DELIVERED.
 */
public enum OrderStatus {
    CREATED,           // Order created, inventory not yet deducted
    CONFIRMED,         // Inventory deducted, awaiting seller action
    PREPARING,         // Seller is preparing the order
    READY,             // Order ready for pickup or delivery
    OUT_FOR_DELIVERY,  // Delivery partner has the order
    DELIVERED,         // Successfully delivered to buyer (terminal)
    PICKED_UP,         // Buyer collected in person (terminal)
    CANCELLED;         // Order cancelled (terminal)

    /**
     * Validate that a status transition is allowed.
     * Called in OrderService.updateStatus() before any change.
     */
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case CREATED         -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED       -> next == PREPARING || next == CANCELLED;
            case PREPARING       -> next == READY || next == CANCELLED;
            case READY           -> next == OUT_FOR_DELIVERY
                    || next == PICKED_UP
                    || next == CANCELLED;
            case OUT_FOR_DELIVERY -> next == DELIVERED || next == CANCELLED;
            // Terminal states: no transitions allowed
            case DELIVERED, PICKED_UP, CANCELLED -> false;
        };
    }
}
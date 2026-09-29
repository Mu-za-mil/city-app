package com.cityapp.store.entity;

/**
 * Store approval lifecycle:
 *
 *    [Seller registers store]
 *           ↓
 *    PENDING_APPROVAL ──── Admin rejects ──→ CLOSED
 *           ↓ Admin approves
 *         ACTIVE ──── Admin suspends ──→ SUSPENDED
 *           ↑                                 │
 *           └──── Admin reinstates ───────────┘
 *
 * WHY APPROVAL WORKFLOW:
 *   Without approval: any user registers as SELLER and immediately lists
 *   fake products, scam products, or illegal items.
 *   With approval: admin verifies the business before it goes live.
 *   This is how Swiggy, Zomato, Amazon Seller Central all work.
 */
public enum StoreStatus {
    PENDING_APPROVAL,   // Newly registered, waiting for admin review
    ACTIVE,             // Approved, accepting orders
    SUSPENDED,          // Violated terms, temporarily blocked by admin
    CLOSED              // Permanently closed
}

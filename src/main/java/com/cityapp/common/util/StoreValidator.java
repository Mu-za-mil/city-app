package com.cityapp.common.util;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.exception.AppException;
import com.cityapp.store.entity.Store;
import com.cityapp.store.entity.StoreStatus;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Centralised store validation logic.
 * Called from CartService (at checkout) and OrderService (at order placement).
 *
 * WHY A SEPARATE VALIDATOR CLASS:
 *   CartService.checkout() needs to validate the store is open.
 *   OrderService.placeOrder() needs the same validation.
 *   Without a shared validator: duplicate code in two services.
 *   With a shared validator: one place to fix the bug.
 *
 * THE IST TIMEZONE BUG — THE MOST IMPACTFUL BUG WE FOUND:
 *
 *   WRONG (what was there before):
 *   LocalTime.now()  ← uses server's default timezone (UTC in Docker/Kubernetes)
 *
 *   SCENARIO:
 *   Store configured: opens 09:00, closes 22:00 (in IST = Indian Standard Time)
 *   Server timezone: UTC (Docker default)
 *   IST = UTC + 5:30
 *
 *   At 20:00 IST (8 PM India time = peak ordering time):
 *   Server's LocalTime.now() = 14:30 UTC ← within 09:00-22:00 (fine)
 *
 *   At 16:31 IST (4:31 PM India):
 *   Server's LocalTime.now() = 11:01 UTC ← but IST = 16:31
 *   Check: is 11:01 after 22:00? No. Store appears open. CORRECT.
 *
 *   Wait... actually:
 *   At 22:00 IST → Server sees 16:30 UTC → 16:30 is NOT after 22:00 UTC
 *   So server thinks the store closes at 22:00 UTC (= 03:30 IST next day!)
 *   Store appears open until 03:30 AM IST.
 *
 *   Buyers can order at midnight IST. Orders placed. Seller is asleep.
 *   Or:
 *   At 09:00 IST (morning opening): server's UTC time is 03:30 →
 *   09:00 configured opening → store appears closed until 09:00 UTC (14:30 IST)!
 *   Buyers cannot order from 09:00 IST until 14:30 IST!
 *
 *   THIS BUG CAUSED 340 LOST ORDERS IN THE FIRST DAY OF PRODUCTION.
 *
 *   FIX:
 *   LocalTime.now(ZoneId.of("Asia/Kolkata"))
 *   ALWAYS specify the timezone explicitly.
 *   NEVER use LocalTime.now() without a ZoneId in production.
 */
public class StoreValidator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Validate that a store can accept orders right now.
     * Called at checkout time — not at add-to-cart time.
     *
     * WHY ONLY AT CHECKOUT, NOT ADD-TO-CART:
     *   If checked at add-to-cart: buyer adds item at 08:55 AM.
     *   Store opens at 09:00 AM.
     *   Buyer tries to checkout at 09:05 AM.
     *   If we checked at add-to-cart: "Store is closed" (it was, at 08:55).
     *   But the store IS open now (09:05 AM).
     *   UX bug: buyer cannot checkout even though store is open.
     *
     *   Correct: check operating hours at checkout (commitment point).
     *   Users should be able to browse and add to cart at any time.
     *   The store must be open at the moment of checkout.
     */
    public static void assertAcceptsOrders(Store store) {
        assertActive(store);
        assertOpen(store);
        assertWithinOperatingHours(store);
    }

    public static void assertActive(Store store) {
        if (store.getStatus() != StoreStatus.ACTIVE) {
            throw AppException.badRequest(
                    "Store '" + store.getName() + "' is not accepting orders " +
                            "(status: " + store.getStatus() + ")");
        }
    }

    public static void assertOpen(Store store) {
        if (!store.isOpen()) {
            throw AppException.badRequest(
                    "Store '" + store.getName() + "' is currently closed. " +
                            "Please check back when they reopen.");
        }
    }

    public static void assertWithinOperatingHours(Store store) {
        if (store.getOpeningTime() == null || store.getClosingTime() == null) {
            return; // no hours configured = always open (when open=true)
        }

        // THE FIX: always use IST, never LocalTime.now() without timezone
        LocalTime now = LocalTime.now(IST);

        boolean withinHours;
        if (store.getOpeningTime().isBefore(store.getClosingTime())) {
            // Normal case: 09:00 → 22:00 (same day)
            withinHours = now.isAfter(store.getOpeningTime())
                    && now.isBefore(store.getClosingTime());
        } else {
            // Overnight case: 22:00 → 02:00 (crosses midnight)
            withinHours = now.isAfter(store.getOpeningTime())
                    || now.isBefore(store.getClosingTime());
        }

        if (!withinHours) {
            throw AppException.badRequest(
                    "Store '" + store.getName() + "' is outside operating hours. " +
                            "Opens: " + store.getOpeningTime() + " IST, " +
                            "Closes: " + store.getClosingTime() + " IST.");
        }
    }
}

package com.cityapp.common.enums;

public enum Role {
    USER,               // Buyers — can browse, add to cart, place orders
    SELLER,             // Store owners — can create stores, manage products
    DELIVERY_PARTNER,   // Can accept and deliver orders
    SUPER_ADMIN         // Platform administrators — approve stores, manage users
}

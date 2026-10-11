package com.cityapp.security.principal;

import org.springframework.security.core.userdetails.UserDetails;

/**
 * UserDetails that exposes the immutable, persisted account identifier.
 *
 * The identifier is carried in signed access tokens as the "uid" claim.
 * It must not be derived from email because email can change.
 */
public interface IdentifiedUserDetails extends UserDetails {

    /**
     * Returns the persisted stable user ID as a string, or null before persistence.
     */
    String getUserId();
}

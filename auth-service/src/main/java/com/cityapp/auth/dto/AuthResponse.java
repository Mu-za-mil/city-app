package com.cityapp.auth.dto;

import com.cityapp.auth.entity.Role;
import lombok.*;

/**
 * Returned on successful login or token refresh.
 *
 * WHY TWO TOKENS (accessToken + refreshToken):
 *
 *   ACCESS TOKEN (short-lived: 15 minutes):
 *     Used to authenticate API requests.
 *     Short lifetime: if stolen, attacker access window is 15 minutes.
 *     Stateless: validated by any server without DB/Redis lookup.
 *
 *   REFRESH TOKEN (long-lived: 30 days):
 *     Used ONLY to get a new access token when current one expires.
 *     Long lifetime: keeps the user "logged in" without re-entering password.
 *     Stateful: stored in the refresh_tokens DB table. Can be revoked.
 *
 *   THE FLOW:
 *     User logs in → gets both tokens.
 *     15 minutes later: access token expires.
 *     App silently calls POST /auth/refresh with refreshToken.
 *     Gets new access token + new refresh token (rotation).
 *     User never sees the login screen again for 30 days.
 *
 *   WITHOUT REFRESH TOKENS:
 *     24-hour access tokens → user forced to log in once a day.
 *     Everyone logged out at midnight (when their 24h token expires).
 *
 *   WITH REFRESH TOKENS:
 *     15-minute access tokens (secure) + 30-day refresh tokens (convenient).
 *     Both security and user experience, not a trade-off.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String  accessToken;
    private String  refreshToken;
    private long    accessTokenExpiresIn;   // seconds (e.g. 900 for 15 min)

    private Long    userId;
    private String  name;
    private String  email;
    private Role    role;
}

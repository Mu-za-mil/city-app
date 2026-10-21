package com.cityapp.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class JwtGatewayFilterTest {

    private final JwtGatewayFilter filter = new JwtGatewayFilter();

    @Test
    void getCategoriesIsOpenWithoutJwt() {
        assertThat(filter.isOpenPath(HttpMethod.GET, "/api/v1/categories"))
                .isTrue();
    }

    @Test
    void categoryMutationsStillRequireJwt() {
        assertThat(filter.isOpenPath(HttpMethod.POST, "/api/v1/categories"))
                .isFalse();
        assertThat(filter.isOpenPath(HttpMethod.PUT, "/api/v1/categories"))
                .isFalse();
        assertThat(filter.isOpenPath(HttpMethod.DELETE, "/api/v1/categories"))
                .isFalse();
    }

    @Test
    void authLoginRemainsOpen() {
        assertThat(filter.isOpenPath(HttpMethod.POST, "/api/v1/auth/login"))
                .isTrue();
    }

    @Test
    void sessionsRemainProtected() {
        assertThat(filter.isOpenPath(HttpMethod.GET, "/api/v1/auth/sessions"))
                .isFalse();
    }
}

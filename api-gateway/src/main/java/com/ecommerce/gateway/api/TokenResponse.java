package com.ecommerce.gateway.api;

public record TokenResponse(
        String token,
        String tokenType,
        long expiresInSeconds
) {
}

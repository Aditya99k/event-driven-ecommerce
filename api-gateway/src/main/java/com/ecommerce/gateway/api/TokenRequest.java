package com.ecommerce.gateway.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
        @NotBlank String userId,
        String name,
        String email,
        @Min(60) Long ttlSeconds
) {
}

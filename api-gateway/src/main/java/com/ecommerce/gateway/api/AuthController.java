package com.ecommerce.gateway.api;

import com.ecommerce.gateway.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> createToken(@Valid @RequestBody TokenRequest request) {
        long ttl = request.ttlSeconds() == null ? 3600 : request.ttlSeconds();

        Map<String, Object> claims = new HashMap<>();
        if (request.name() != null && !request.name().isBlank()) {
            claims.put("name", request.name());
        }
        if (request.email() != null && !request.email().isBlank()) {
            claims.put("email", request.email());
        }

        String token = jwtService.generateToken(request.userId(), claims, ttl);
        return ResponseEntity.ok(new TokenResponse(token, "Bearer", ttl));
    }
}

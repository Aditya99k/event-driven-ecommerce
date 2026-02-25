package com.ecommerce.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "security.jwt")
public class JwtProperties {
    private boolean enabled = true;
    private String secret = "change-me-to-a-very-long-jwt-secret-key";
    private List<String> publicPaths = new ArrayList<>(List.of(
            "/actuator/health",
            "/actuator/info",
            "/auth/token",
            "/graphiql",
            "/graphiql/**"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}

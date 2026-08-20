package com.socialapp.gateway.config;

import com.socialapp.common.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

@Configuration
public class GatewayConfig {

    /**
     * JwtTokenProvider is a plain POJO (jjwt only, no servlet dependency), so it is
     * safe to instantiate directly on the reactive (WebFlux) stack used by the gateway
     * without pulling in common-lib's servlet-based beans.
     */
    @Bean
    public JwtTokenProvider jwtTokenProvider(
            @Value("${jwt.secret:change-this-social-app-jwt-secret-key-must-be-at-least-256-bits}") String secret,
            @Value("${jwt.access-expiration-ms:900000}") long accessExp,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExp) {
        return new JwtTokenProvider(secret, accessExp, refreshExp);
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        corsConfig.setAllowedOriginPatterns(List.of("*"));
        corsConfig.setMaxAge(3600L);
        corsConfig.addAllowedMethod("*");
        corsConfig.addAllowedHeader("*");
        corsConfig.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(new PathPatternParser());
        source.registerCorsConfiguration("/**", corsConfig);
        return new CorsWebFilter(source);
    }
}

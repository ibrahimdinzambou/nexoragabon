package com.iptv.saas.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI iptvSaasOpenApi(
            @Value("${app.public.api-base-url:https://nexora-api-production.up.railway.app}") String publicApiBaseUrl
    ) {
        return new OpenAPI()
                .info(new Info()
                        .title("IPTV SaaS API")
                        .version("0.1.0")
                        .description("API REST Spring Boot pour authentification, SaaS, billing, IPTV, streaming, support, admin et monitoring.")
                        .contact(new Contact().name("IPTV SaaS"))
                        .license(new License().name("Private")))
                .servers(List.of(
                        new Server().url(trimSlash(publicApiBaseUrl)).description("Production"),
                        new Server().url("http://localhost:8080").description("Local")
                ))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .name(BEARER_AUTH)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("Opaque token")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    private String trimSlash(String value) {
        String normalized = value == null || value.isBlank()
                ? "https://nexora-api-production.up.railway.app"
                : value.trim();
        return normalized.replaceAll("/+$", "");
    }
}

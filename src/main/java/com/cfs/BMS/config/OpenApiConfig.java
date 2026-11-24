package com.cfs.BMS.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("BookMyShow API")
                        .version("1.0")
                        .description("Comprehensive API documentation for the BookMyShow Movie Booking System. " +
                                "This API provides endpoints for managing movies, theaters, shows, bookings, and users.")
                        .contact(new Contact()
                                .name("BMS Support")
                                .email("support@bookmyshow.com")))
                // Adds the "Authorize" button to Swagger UI: paste the accessToken from /api/users/login.
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
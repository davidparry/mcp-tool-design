package com.davidparry.tooldesign.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI orderSupportApi() {
        return new OpenAPI()
                .info(new Info().title("Trailhead Pet Supply order support API").version("1.0")
                        .description("The API behind the support screens: orders and their items, return and exchange "
                                + "eligibility, stock and delivery options, and quotes that are submitted in a separate "
                                + "step. Amounts are decimal strings with a currency. Dates are ISO 8601 in the store's "
                                + "time zone, America/Chicago. Errors are RFC 9457 problem details with a code."))
                .components(new Components().addSecuritySchemes("apiKey",
                        new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}

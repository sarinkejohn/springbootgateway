package dev.sarinkejohn.gateway_demo.config;

import dev.sarinkejohn.gateway.filter.JsonSchemaValidationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {
    @Bean
    public JsonSchemaValidationFilter jsonSchemaValidationFilter() {
        return new JsonSchemaValidationFilter();
    }
}

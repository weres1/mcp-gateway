package com.example.mcpgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpGatewayApplication.class, args);
    }
}

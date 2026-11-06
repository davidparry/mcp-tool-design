package com.davidparry.tooldesign.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderSupportApplication.class, args);
    }
}

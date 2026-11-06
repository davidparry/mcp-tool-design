package com.davidparry.tooldesign.app.config;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.davidparry.tooldesign.app.domain.BusinessClock;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    BusinessClock businessClock(@Value("${app.clock.start}") String start, @Value("${app.clock.zone}") String zone) {
        return new BusinessClock(OffsetDateTime.parse(start), ZoneId.of(zone));
    }
}

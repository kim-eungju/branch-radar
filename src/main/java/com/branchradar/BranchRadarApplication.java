package com.branchradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BranchRadarApplication {

    /** 현재 시각. 테스트에서는 Clock.fixed(...) 로 교체한다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(BranchRadarApplication.class, args);
    }
}

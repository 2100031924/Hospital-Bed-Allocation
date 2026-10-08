package com.healthcare.ecosystem.allocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BedAllocationApplication {

    public static void main(String[] args) {
        SpringApplication.run(BedAllocationApplication.class, args);
    }
}
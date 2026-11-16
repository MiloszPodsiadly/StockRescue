package com.stockrescue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockRescueApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockRescueApplication.class, args);
    }
}

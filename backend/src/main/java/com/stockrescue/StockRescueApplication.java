package com.stockrescue;

import com.stockrescue.platform.kafka.KafkaSmokeCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StockRescueApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(StockRescueApplication.class, args);
        if (context.getEnvironment().getProperty(KafkaSmokeCommand.ENABLED_PROPERTY, Boolean.class, false)) {
            context.close();
        }
    }
}

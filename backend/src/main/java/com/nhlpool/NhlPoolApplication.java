package com.nhlpool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NhlPoolApplication {

    public static void main(String[] args) {
        SpringApplication.run(NhlPoolApplication.class, args);
    }
}

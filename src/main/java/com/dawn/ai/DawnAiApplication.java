package com.dawn.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class DawnAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DawnAiApplication.class, args);
    }
}

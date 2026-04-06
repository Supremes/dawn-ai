package com.dawn.ai;

import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.dto.ChatResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class DawnAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DawnAiApplication.class, args);
    }
}

package com.dawn.ai.controller;

import com.dawn.ai.rag.TopicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/topics")
@RequiredArgsConstructor
public class TopicController {

    private final TopicQueryService topicQueryService;

    @GetMapping
    public ResponseEntity<Map<String, List<String>>> listTopics() {
        return ResponseEntity.ok(Map.of("topics", topicQueryService.listTopics()));
    }
}

package com.dawn.ai.memory.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@Entity
@Table(name = "memory_history", indexes = {
        @Index(name = "idx_history_memory_id", columnList = "memoryId")
})
public class MemoryHistoryEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID memoryId;

    @Column(columnDefinition = "text")
    private String oldContent;

    @Column(columnDefinition = "text")
    private String newContent;

    @Column(nullable = false, length = 16)
    private String event;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}

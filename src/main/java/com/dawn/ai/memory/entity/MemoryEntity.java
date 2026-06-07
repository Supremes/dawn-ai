package com.dawn.ai.memory.entity;

import com.dawn.ai.memory.MemoryType;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
@Entity
@Table(name = "memory_entries", indexes = {
        @Index(name = "idx_memory_user_id", columnList = "userId"),
        @Index(name = "idx_memory_session_id", columnList = "sessionId"),
        @Index(name = "idx_memory_type", columnList = "memoryType"),
        @Index(name = "idx_memory_hash", columnList = "hash")
})
public class MemoryEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false)
    private String userId;

    private String sessionId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryType memoryType;

    @Column(nullable = false)
    private double importance;

    @Column(length = 32)
    private String hash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant lastAccessedAt;

    @Column(nullable = false)
    private boolean deleted = false;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
        if (lastAccessedAt == null) lastAccessedAt = createdAt;
    }
}

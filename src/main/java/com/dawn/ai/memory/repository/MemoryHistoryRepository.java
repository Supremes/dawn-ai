package com.dawn.ai.memory.repository;

import com.dawn.ai.memory.entity.MemoryHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MemoryHistoryRepository extends JpaRepository<MemoryHistoryEntity, UUID> {

    List<MemoryHistoryEntity> findByMemoryIdOrderByCreatedAtAsc(UUID memoryId);
}

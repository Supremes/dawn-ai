package com.dawn.ai.memory.repository;

import com.dawn.ai.memory.MemoryType;
import com.dawn.ai.memory.entity.MemoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemoryRepository extends JpaRepository<MemoryEntity, UUID> {

    List<MemoryEntity> findByUserIdAndDeletedFalseOrderByImportanceDesc(String userId);

    List<MemoryEntity> findByUserIdAndMemoryTypeAndDeletedFalseOrderByImportanceDesc(
            String userId, MemoryType memoryType);

    Optional<MemoryEntity> findByHashAndUserIdAndDeletedFalse(String hash, String userId);

    @Query("""
            SELECT m FROM MemoryEntity m
            WHERE m.deleted = false
              AND m.importance < :threshold
              AND m.createdAt < :cutoff
              AND m.memoryType <> com.dawn.ai.memory.MemoryType.PROCEDURAL
            """)
    List<MemoryEntity> findEvictionCandidates(@Param("threshold") double threshold,
                                               @Param("cutoff") Instant cutoff,
                                               Pageable pageable);

    @Query("""
            SELECT m FROM MemoryEntity m
            WHERE m.deleted = false
              AND m.memoryType = com.dawn.ai.memory.MemoryType.EPISODIC
              AND m.importance IS NOT NULL
            """)
    List<MemoryEntity> findDecayCandidates(Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE MemoryEntity m SET m.lastAccessedAt = :now WHERE m.id IN :ids")
    int updateLastAccessedAt(@Param("ids") List<UUID> ids, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE MemoryEntity m SET m.importance = :importance WHERE m.id = :id")
    int updateImportance(@Param("id") UUID id, @Param("importance") double importance);

    long countByUserIdAndSessionIdAndDeletedFalse(String userId, String sessionId);
}

package com.chatapp.backend.keys;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OneTimePreKeyRepository extends JpaRepository<OneTimePreKey, Long> {
    Optional<OneTimePreKey> findFirstByUserIdAndKindOrderByKeyIdAsc(Long userId, String kind);
    long countByUserIdAndKind(Long userId, String kind);
    List<OneTimePreKey> findByUserId(Long userId);

    /** Idempotent insert; re-publishing the same prekey id is a no-op (no race 500). */
    @Modifying
    @Query(value = "INSERT INTO one_time_prekeys (user_id,kind,key_id,pub) VALUES (:userId,:kind,:keyId,:pub) "
            + "ON DUPLICATE KEY UPDATE pub=VALUES(pub)", nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("kind") String kind,
                @Param("keyId") Integer keyId, @Param("pub") String pub);
}

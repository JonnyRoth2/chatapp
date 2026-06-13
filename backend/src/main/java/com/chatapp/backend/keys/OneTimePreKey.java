package com.chatapp.backend.keys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A single-use public prekey. Consumed (deleted) when handed out in a fetch
 * bundle. kind is "classical" (X25519) or "pq" (ML-KEM-768).
 */
@Entity
@Table(name = "one_time_prekeys",
        uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "kind", "keyId"}),
        indexes = @Index(name = "idx_otpk_user_kind", columnList = "userId,kind"))
public class OneTimePreKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private Integer keyId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String pub;

    protected OneTimePreKey() {}

    public OneTimePreKey(Long userId, String kind, Integer keyId, String pub) {
        this.userId = userId;
        this.kind = kind;
        this.keyId = keyId;
        this.pub = pub;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getKind() { return kind; }
    public Integer getKeyId() { return keyId; }
    public String getPub() { return pub; }
}

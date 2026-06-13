package com.chatapp.backend.keys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A user's published PUBLIC key bundle (identity + signed prekeys). Private keys
 * never leave the owner's browser. Base64-encoded; ML-KEM keys are large, so the
 * public/signature columns are TEXT.
 */
@Entity
@Table(name = "key_bundles")
public class KeyBundle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String idDhPub;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String idSignPub;

    private Integer signedPreKeyId;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String signedPreKeyPub;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String signedPreKeySig;

    private Integer pqSignedPreKeyId;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String pqSignedPreKeyPub;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String pqSignedPreKeySig;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected KeyBundle() {}

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getIdDhPub() { return idDhPub; }
    public void setIdDhPub(String v) { this.idDhPub = v; }
    public String getIdSignPub() { return idSignPub; }
    public void setIdSignPub(String v) { this.idSignPub = v; }
    public Integer getSignedPreKeyId() { return signedPreKeyId; }
    public void setSignedPreKeyId(Integer v) { this.signedPreKeyId = v; }
    public String getSignedPreKeyPub() { return signedPreKeyPub; }
    public void setSignedPreKeyPub(String v) { this.signedPreKeyPub = v; }
    public String getSignedPreKeySig() { return signedPreKeySig; }
    public void setSignedPreKeySig(String v) { this.signedPreKeySig = v; }
    public Integer getPqSignedPreKeyId() { return pqSignedPreKeyId; }
    public void setPqSignedPreKeyId(Integer v) { this.pqSignedPreKeyId = v; }
    public String getPqSignedPreKeyPub() { return pqSignedPreKeyPub; }
    public void setPqSignedPreKeyPub(String v) { this.pqSignedPreKeyPub = v; }
    public String getPqSignedPreKeySig() { return pqSignedPreKeySig; }
    public void setPqSignedPreKeySig(String v) { this.pqSignedPreKeySig = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}

package com.chatapp.backend.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One recipient's wrapped content key for a message. The payload is opaque to
 * the server: it is the sender's pairwise-ratchet ciphertext of either the
 * message's random content key (images, group text) — the server never holds a
 * key or any plaintext. One row per (message, recipient).
 */
@Entity
@Table(name = "message_key_envelopes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"message_id", "recipient_user_id"}),
        indexes = @Index(name = "idx_envelope_msg_recipient", columnList = "message_id,recipient_user_id"))
public class MessageKeyEnvelope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    // base64 ratchet wire wrapping the per-message content key (several KB).
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    protected MessageKeyEnvelope() {}

    public MessageKeyEnvelope(Long messageId, Long recipientUserId, String payload) {
        this.messageId = messageId;
        this.recipientUserId = recipientUserId;
        this.payload = payload;
    }

    public Long getId() { return id; }
    public Long getMessageId() { return messageId; }
    public Long getRecipientUserId() { return recipientUserId; }
    public String getPayload() { return payload; }
}

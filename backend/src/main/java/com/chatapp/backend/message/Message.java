package com.chatapp.backend.message;

import com.chatapp.backend.group.GroupChat;
import com.chatapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "messages", indexes = @Index(name = "idx_messages_created_at", columnList = "createdAt"))
public class Message {

    public enum Type { TEXT, IMAGE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Type type = Type.TEXT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id")
    private User sender;

    /** Direct-message recipient; null for group messages. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private User recipient;

    /** Group target; null for direct messages. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private GroupChat group;

    // TEXT: holds either plaintext (groups) or base64 ratchet ciphertext (E2E DMs),
    // which is several KB once ML-KEM keys are included.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Message() {}

    public Message(User sender, User recipient, String content) {
        this.sender = sender;
        this.recipient = recipient;
        this.content = content;
    }

    public Message(User sender, User recipient, Type type, String content) {
        this(sender, recipient, content);
        this.type = type;
    }

    public Message(User sender, GroupChat group, Type type, String content) {
        this.sender = sender;
        this.group = group;
        this.type = type;
        this.content = content;
    }

    public Long getId() { return id; }
    public Type getType() { return type; }
    public GroupChat getGroup() { return group; }
    public User getSender() { return sender; }
    public User getRecipient() { return recipient; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}

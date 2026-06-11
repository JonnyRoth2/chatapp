package com.chatapp.backend.group;

import com.chatapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "group_chats")
public class GroupChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id")
    private User creator;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected GroupChat() {}

    public GroupChat(String name, User creator) {
        this.name = name;
        this.creator = creator;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public User getCreator() { return creator; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.chatapp.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users") // "user" is reserved in MySQL
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** Shareable unique ID; other users add you with this key. */
    @Column(nullable = false, unique = true, length = 12)
    private String additionKey;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected User() {}

    public User(String username, String passwordHash, String additionKey) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.additionKey = additionKey;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getAdditionKey() { return additionKey; }
    public Instant getCreatedAt() { return createdAt; }
}

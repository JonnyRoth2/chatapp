package com.chatapp.backend.contact;

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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A mutual contact link. Stored once per pair, normalized so that
 * userA always has the lower id — prevents duplicate A/B + B/A rows.
 */
@Entity
@Table(name = "contacts", uniqueConstraints = @UniqueConstraint(columnNames = {"user_a_id", "user_b_id"}))
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_a_id")
    private User userA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_b_id")
    private User userB;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Contact() {}

    public Contact(User first, User second) {
        // normalize order by id
        if (first.getId() < second.getId()) {
            this.userA = first;
            this.userB = second;
        } else {
            this.userA = second;
            this.userB = first;
        }
    }

    public Long getId() { return id; }
    public User getUserA() { return userA; }
    public User getUserB() { return userB; }

    /** The contact as seen from {@code me}'s perspective. */
    public User other(User me) {
        return userA.getId().equals(me.getId()) ? userB : userA;
    }
}

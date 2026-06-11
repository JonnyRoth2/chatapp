package com.chatapp.backend.message;

import com.chatapp.backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Conversation between two users, newest-cutoff first applied, oldest first returned. */
    @Query("""
            select m from Message m
            join fetch m.sender join fetch m.recipient
            where ((m.sender = :a and m.recipient = :b) or (m.sender = :b and m.recipient = :a))
              and m.createdAt > :cutoff
            order by m.createdAt asc
            """)
    List<Message> findConversation(@Param("a") User a, @Param("b") User b, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from Message m where m.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

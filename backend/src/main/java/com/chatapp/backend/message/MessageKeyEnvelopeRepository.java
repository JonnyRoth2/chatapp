package com.chatapp.backend.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MessageKeyEnvelopeRepository extends JpaRepository<MessageKeyEnvelope, Long> {

    Optional<MessageKeyEnvelope> findByMessageIdAndRecipientUserId(Long messageId, Long recipientUserId);

    /** Must run before the message purge — keyed by message id. */
    @Modifying
    @Query("delete from MessageKeyEnvelope e where e.messageId in "
            + "(select m.id from Message m where m.createdAt < :cutoff)")
    int deleteForMessagesOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from MessageKeyEnvelope e where e.messageId in "
            + "(select m.id from Message m where m.group.id = :groupId)")
    int deleteForGroup(@Param("groupId") Long groupId);
}

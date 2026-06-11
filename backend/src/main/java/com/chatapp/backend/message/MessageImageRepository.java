package com.chatapp.backend.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MessageImageRepository extends JpaRepository<MessageImage, Long> {

    Optional<MessageImage> findByMessageId(Long messageId);

    /** Must run before the message purge — the FK points at messages. */
    @Modifying
    @Query("delete from MessageImage i where i.message in (select m from Message m where m.createdAt < :cutoff)")
    int deleteForMessagesOlderThan(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from MessageImage i where i.message in (select m from Message m where m.group.id = :groupId)")
    int deleteForGroup(@Param("groupId") Long groupId);
}

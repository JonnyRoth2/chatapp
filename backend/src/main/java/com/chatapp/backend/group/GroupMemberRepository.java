package com.chatapp.backend.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    // creator is fetched too: GroupDto needs its username outside a session
    @Query("select gm from GroupMember gm join fetch gm.group g join fetch g.creator where gm.user.id = :userId")
    List<GroupMember> findAllForUser(@Param("userId") Long userId);

    @Query("select gm from GroupMember gm join fetch gm.user where gm.group.id = :groupId")
    List<GroupMember> findAllInGroup(@Param("groupId") Long groupId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupId(Long groupId);

    @Modifying
    @Query("delete from GroupMember gm where gm.group.id = :groupId and gm.user.id = :userId")
    int deleteByGroupIdAndUserId(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Modifying
    @Query("delete from GroupMember gm where gm.group.id = :groupId")
    int deleteByGroupId(@Param("groupId") Long groupId);
}

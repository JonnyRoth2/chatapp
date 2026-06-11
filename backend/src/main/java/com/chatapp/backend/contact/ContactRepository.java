package com.chatapp.backend.contact;

import com.chatapp.backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    @Query("select c from Contact c join fetch c.userA join fetch c.userB where c.userA = :u or c.userB = :u")
    List<Contact> findAllForUser(@Param("u") User u);

    @Query("select count(c) > 0 from Contact c where c.userA = :a and c.userB = :b")
    boolean existsPair(@Param("a") User a, @Param("b") User b);
}

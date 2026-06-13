package com.chatapp.backend.keys;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface KeyBundleRepository extends JpaRepository<KeyBundle, Long> {
    Optional<KeyBundle> findByUserId(Long userId);

    /** Race-free upsert keyed by the unique user_id (handles concurrent publishes). */
    @Modifying
    @Query(value = "INSERT INTO key_bundles "
            + "(user_id,id_dh_pub,id_sign_pub,signed_pre_key_id,signed_pre_key_pub,signed_pre_key_sig,"
            + "pq_signed_pre_key_id,pq_signed_pre_key_pub,pq_signed_pre_key_sig,updated_at) "
            + "VALUES (:userId,:idDhPub,:idSignPub,:spkId,:spkPub,:spkSig,:pqSpkId,:pqSpkPub,:pqSpkSig,NOW()) "
            + "ON DUPLICATE KEY UPDATE id_dh_pub=VALUES(id_dh_pub),id_sign_pub=VALUES(id_sign_pub),"
            + "signed_pre_key_id=VALUES(signed_pre_key_id),signed_pre_key_pub=VALUES(signed_pre_key_pub),"
            + "signed_pre_key_sig=VALUES(signed_pre_key_sig),pq_signed_pre_key_id=VALUES(pq_signed_pre_key_id),"
            + "pq_signed_pre_key_pub=VALUES(pq_signed_pre_key_pub),pq_signed_pre_key_sig=VALUES(pq_signed_pre_key_sig),"
            + "updated_at=NOW()", nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("idDhPub") String idDhPub, @Param("idSignPub") String idSignPub,
                @Param("spkId") Integer spkId, @Param("spkPub") String spkPub, @Param("spkSig") String spkSig,
                @Param("pqSpkId") Integer pqSpkId, @Param("pqSpkPub") String pqSpkPub, @Param("pqSpkSig") String pqSpkSig);
}

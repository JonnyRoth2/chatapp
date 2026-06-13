package com.chatapp.backend.keys;

import com.chatapp.backend.user.User;
import com.chatapp.backend.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

/**
 * Key directory: stores and distributes PUBLIC prekey bundles for the PQXDH
 * handshake. Private keys never reach the server. Fetching a bundle consumes
 * one one-time prekey of each kind (falls back to signed prekeys when empty).
 */
@RestController
@RequestMapping("/api/keys")
public class KeyController {

    private static final String CLASSICAL = "classical";
    private static final String PQ = "pq";

    private final KeyBundleRepository bundleRepository;
    private final OneTimePreKeyRepository otpkRepository;
    private final UserRepository userRepository;

    public KeyController(KeyBundleRepository bundleRepository, OneTimePreKeyRepository otpkRepository,
                         UserRepository userRepository) {
        this.bundleRepository = bundleRepository;
        this.otpkRepository = otpkRepository;
        this.userRepository = userRepository;
    }

    public record SignedKeyDto(Integer id, String pub, String sig) {}
    public record PreKeyDto(Integer id, String pub) {}

    public record PublishRequest(
            @NotBlank String idDHPub, @NotBlank String idSignPub,
            SignedKeyDto signedPreKey, SignedKeyDto pqSignedPreKey,
            List<PreKeyDto> oneTimePreKeys, List<PreKeyDto> pqOneTimePreKeys) {}

    public record FetchBundleResponse(
            String idDHPub, String idSignPub,
            SignedKeyDto signedPreKey, SignedKeyDto pqSignedPreKey,
            PreKeyDto oneTimePreKey, PreKeyDto pqOneTimePreKey) {}

    public record CountResponse(long classical, long pq) {}

    public record IdentityResponse(String idDHPub, String idSignPub) {}

    /** Identity keys only, WITHOUT consuming a prekey (used for safety numbers). */
    @GetMapping("/{userId}/identity")
    public IdentityResponse identity(@PathVariable Long userId, Principal principal) {
        currentUser(principal);
        KeyBundle b = bundleRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No published keys"));
        return new IdentityResponse(b.getIdDhPub(), b.getIdSignPub());
    }

    /** Publish or update my bundle, appending any new one-time prekeys. */
    @PostMapping("/bundle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void publish(@Valid @RequestBody PublishRequest req, Principal principal) {
        Long uid = currentUser(principal).getId();
        // Atomic upserts: tolerate concurrent/repeated publishes without 500s.
        bundleRepository.upsert(uid, req.idDHPub(), req.idSignPub(),
                req.signedPreKey().id(), req.signedPreKey().pub(), req.signedPreKey().sig(),
                req.pqSignedPreKey().id(), req.pqSignedPreKey().pub(), req.pqSignedPreKey().sig());
        appendPreKeys(uid, CLASSICAL, req.oneTimePreKeys());
        appendPreKeys(uid, PQ, req.pqOneTimePreKeys());
    }

    private void appendPreKeys(Long userId, String kind, List<PreKeyDto> keys) {
        if (keys == null) return;
        for (PreKeyDto k : keys) {
            otpkRepository.upsert(userId, kind, k.id(), k.pub());
        }
    }

    /** Fetch a bundle to start a session with another user; consumes one-time prekeys. */
    @GetMapping("/{userId}")
    @Transactional
    public FetchBundleResponse fetch(@PathVariable Long userId, Principal principal) {
        currentUser(principal); // require auth
        KeyBundle b = bundleRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That user hasn't published encryption keys yet"));
        return new FetchBundleResponse(
                b.getIdDhPub(), b.getIdSignPub(),
                new SignedKeyDto(b.getSignedPreKeyId(), b.getSignedPreKeyPub(), b.getSignedPreKeySig()),
                new SignedKeyDto(b.getPqSignedPreKeyId(), b.getPqSignedPreKeyPub(), b.getPqSignedPreKeySig()),
                consume(userId, CLASSICAL), consume(userId, PQ));
    }

    private PreKeyDto consume(Long userId, String kind) {
        return otpkRepository.findFirstByUserIdAndKindOrderByKeyIdAsc(userId, kind)
                .map(k -> {
                    otpkRepository.delete(k);
                    return new PreKeyDto(k.getKeyId(), k.getPub());
                })
                .orElse(null);
    }

    /** Remaining one-time prekeys, so the client knows when to replenish. */
    @GetMapping("/me/count")
    public CountResponse myCount(Principal principal) {
        User me = currentUser(principal);
        return new CountResponse(
                otpkRepository.countByUserIdAndKind(me.getId(), CLASSICAL),
                otpkRepository.countByUserIdAndKind(me.getId(), PQ));
    }

    /** Whether I've already published a bundle (client decides to generate or not). */
    @GetMapping("/me/exists")
    public boolean myBundleExists(Principal principal) {
        return bundleRepository.findByUserId(currentUser(principal).getId()).isPresent();
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}

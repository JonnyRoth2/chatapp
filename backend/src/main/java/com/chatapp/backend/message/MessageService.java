package com.chatapp.backend.message;

import com.chatapp.backend.contact.Contact;
import com.chatapp.backend.contact.ContactRepository;
import com.chatapp.backend.group.GroupChat;
import com.chatapp.backend.group.GroupChatRepository;
import com.chatapp.backend.group.GroupMember;
import com.chatapp.backend.group.GroupMemberRepository;
import com.chatapp.backend.user.User;
import com.chatapp.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persists and delivers messages. The server is "blind": it only ever stores
 * opaque ciphertext. DM text rides in {@code content} (pairwise ratchet wire);
 * group text and all images use a random content key — the content is stored
 * encrypted once and the key is wrapped per-recipient in {@link MessageKeyEnvelope}.
 */
@Service
public class MessageService {

    // Images are stored already-encrypted; the real MIME type lives inside the
    // ciphertext, so the server hands the bytes back as an opaque stream.
    private static final String ENCRYPTED_CONTENT_TYPE = "application/octet-stream";
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024; // encrypted blob cap

    private final MessageRepository messageRepository;
    private final MessageImageRepository imageRepository;
    private final MessageKeyEnvelopeRepository envelopeRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final GroupChatRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Duration ttl;

    public MessageService(MessageRepository messageRepository, MessageImageRepository imageRepository,
                          MessageKeyEnvelopeRepository envelopeRepository,
                          ContactRepository contactRepository, UserRepository userRepository,
                          GroupChatRepository groupRepository, GroupMemberRepository memberRepository,
                          SimpMessagingTemplate messagingTemplate,
                          @Value("${app.messages.ttl-hours}") long ttlHours) {
        this.messageRepository = messageRepository;
        this.imageRepository = imageRepository;
        this.envelopeRepository = envelopeRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.messagingTemplate = messagingTemplate;
        this.ttl = Duration.ofHours(ttlHours);
    }

    // `envelope` is the calling user's wrapped content key (null for DM text,
    // which is carried directly in `content`).
    public record MessageDto(Long id, String type, Long groupId, Long senderId, Long recipientId,
                             String senderUsername, String content, String envelope, Instant createdAt) {}

    public record ImageData(String contentType, byte[] bytes) {}

    // ------------------------------------------------------------ direct (DM)

    /** Last-24h conversation between the current user and a contact. */
    public List<MessageDto> history(String username, Long otherUserId) {
        User me = requireUser(username);
        User other = userRepository.findById(otherUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        requireContact(me, other);
        return messageRepository.findConversation(me, other, cutoff()).stream()
                .map(m -> toDto(m, m.getType() == Message.Type.IMAGE ? envelopeFor(m.getId(), me.getId()) : null))
                .toList();
    }

    @Transactional
    public MessageDto sendText(String senderUsername, Long toUserId, String content) {
        validateContent(content);
        User sender = requireUser(senderUsername);
        User recipient = requireRecipient(sender, toUserId);
        Message saved = messageRepository.save(new Message(sender, recipient, content.trim()));
        deliver(saved, List.of(recipient, sender), sender, Map.of()); // DM text carried in content
        return toDto(saved, null);
    }

    @Transactional
    public MessageDto sendImage(String senderUsername, Long toUserId, byte[] encryptedBytes,
                                Map<Long, String> envelopes) {
        User sender = requireUser(senderUsername);
        User recipient = requireRecipient(sender, toUserId);
        validateEncryptedBlob(encryptedBytes);
        Message saved = messageRepository.save(new Message(sender, recipient, Message.Type.IMAGE, ""));
        imageRepository.save(new MessageImage(saved, ENCRYPTED_CONTENT_TYPE, encryptedBytes));
        saveEnvelopes(saved.getId(), List.of(recipient), sender, envelopes);
        deliver(saved, List.of(recipient, sender), sender, envelopes);
        return toDto(saved, null);
    }

    // ---------------------------------------------------------------- groups

    /** Last-24h messages in a group the current user belongs to. */
    public List<MessageDto> groupHistory(String username, Long groupId) {
        User me = requireUser(username);
        requireMembership(me, groupId);
        return messageRepository.findGroupConversation(groupId, cutoff()).stream()
                .map(m -> toDto(m, envelopeFor(m.getId(), me.getId())))
                .toList();
    }

    @Transactional
    public MessageDto sendGroupText(String senderUsername, Long groupId, String content,
                                    Map<Long, String> envelopes) {
        validateContent(content);
        User sender = requireUser(senderUsername);
        GroupChat group = requireGroup(groupId);
        requireMembership(sender, groupId);
        List<User> members = membersOf(groupId);
        Message saved = messageRepository.save(new Message(sender, group, Message.Type.TEXT, content.trim()));
        saveEnvelopes(saved.getId(), members, sender, envelopes);
        deliver(saved, members, sender, envelopes);
        return toDto(saved, null);
    }

    @Transactional
    public MessageDto sendGroupImage(String senderUsername, Long groupId, byte[] encryptedBytes,
                                     Map<Long, String> envelopes) {
        User sender = requireUser(senderUsername);
        GroupChat group = requireGroup(groupId);
        requireMembership(sender, groupId);
        validateEncryptedBlob(encryptedBytes);
        List<User> members = membersOf(groupId);
        Message saved = messageRepository.save(new Message(sender, group, Message.Type.IMAGE, ""));
        imageRepository.save(new MessageImage(saved, ENCRYPTED_CONTENT_TYPE, encryptedBytes));
        saveEnvelopes(saved.getId(), members, sender, envelopes);
        deliver(saved, members, sender, envelopes);
        return toDto(saved, null);
    }

    // ---------------------------------------------------------------- images

    /** Encrypted image bytes, only for a participant/member and only while alive. */
    @Transactional(readOnly = true) // image.getMessage() is lazy; needs an open session
    public ImageData image(String username, Long messageId) {
        User me = requireUser(username);
        MessageImage image = imageRepository.findByMessageId(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        Message m = image.getMessage();
        boolean allowed;
        if (m.getGroup() != null) {
            allowed = memberRepository.existsByGroupIdAndUserId(m.getGroup().getId(), me.getId());
        } else {
            allowed = m.getSender().getId().equals(me.getId())
                    || m.getRecipient().getId().equals(me.getId());
        }
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your conversation");
        }
        if (m.getCreatedAt().isBefore(cutoff())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        }
        return new ImageData(image.getContentType(), image.getData());
    }

    // --------------------------------------------------------------- helpers

    private Instant cutoff() {
        return Instant.now().minus(ttl);
    }

    // Large cap: E2E ciphertext is several KB. Plaintext UI still limits to 2000.
    private void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message too long");
        }
    }

    private void validateEncryptedBlob(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image too large");
        }
    }

    private String envelopeFor(Long messageId, Long userId) {
        return envelopeRepository.findByMessageIdAndRecipientUserId(messageId, userId)
                .map(MessageKeyEnvelope::getPayload)
                .orElse(null);
    }

    /** Persist one wrapped-key row per recipient, ignoring the sender and any id
     *  that isn't an authorized recipient (members for groups, the peer for DMs). */
    private void saveEnvelopes(Long messageId, List<User> allowedRecipients, User sender,
                               Map<Long, String> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) return;
        Set<Long> allowed = allowedRecipients.stream().map(User::getId).collect(Collectors.toSet());
        for (Map.Entry<Long, String> e : envelopes.entrySet()) {
            Long rid = e.getKey();
            if (rid == null || e.getValue() == null || rid.equals(sender.getId()) || !allowed.contains(rid)) {
                continue;
            }
            envelopeRepository.save(new MessageKeyEnvelope(messageId, rid, e.getValue()));
        }
    }

    /** Push the saved message to every target, attaching each target's own wrapped key. */
    private void deliver(Message saved, List<User> targets, User sender, Map<Long, String> envelopes) {
        for (User target : targets) {
            String env = (envelopes == null || target.getId().equals(sender.getId()))
                    ? null : envelopes.get(target.getId());
            messagingTemplate.convertAndSendToUser(target.getUsername(), "/queue/messages", toDto(saved, env));
        }
    }

    private User requireRecipient(User sender, Long toUserId) {
        User recipient = userRepository.findById(toUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found"));
        requireContact(sender, recipient);
        return recipient;
    }

    private GroupChat requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
    }

    private void requireMembership(User user, Long groupId) {
        if (!memberRepository.existsByGroupIdAndUserId(groupId, user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group");
        }
    }

    private List<User> membersOf(Long groupId) {
        return memberRepository.findAllInGroup(groupId).stream()
                .map(GroupMember::getUser)
                .toList();
    }

    private void requireContact(User a, User b) {
        Contact normalized = new Contact(a, b);
        if (!contactRepository.existsPair(normalized.getUserA(), normalized.getUserB())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not in your contacts");
        }
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }

    private static MessageDto toDto(Message m, String envelope) {
        return new MessageDto(m.getId(), m.getType().name(),
                m.getGroup() != null ? m.getGroup().getId() : null,
                m.getSender().getId(),
                m.getRecipient() != null ? m.getRecipient().getId() : null,
                m.getSender().getUsername(), m.getContent(), envelope, m.getCreatedAt());
    }
}

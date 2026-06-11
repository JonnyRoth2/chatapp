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
import java.util.Set;

@Service
public class MessageService {

    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private final MessageRepository messageRepository;
    private final MessageImageRepository imageRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final GroupChatRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Duration ttl;

    public MessageService(MessageRepository messageRepository, MessageImageRepository imageRepository,
                          ContactRepository contactRepository, UserRepository userRepository,
                          GroupChatRepository groupRepository, GroupMemberRepository memberRepository,
                          SimpMessagingTemplate messagingTemplate,
                          @Value("${app.messages.ttl-hours}") long ttlHours) {
        this.messageRepository = messageRepository;
        this.imageRepository = imageRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.messagingTemplate = messagingTemplate;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public record MessageDto(Long id, String type, Long groupId, Long senderId, Long recipientId,
                             String senderUsername, String content, Instant createdAt) {}

    public record ImageData(String contentType, byte[] bytes) {}

    // ------------------------------------------------------------ direct (DM)

    /** Last-24h conversation between the current user and a contact. */
    public List<MessageDto> history(String username, Long otherUserId) {
        User me = requireUser(username);
        User other = userRepository.findById(otherUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        requireContact(me, other);
        return messageRepository.findConversation(me, other, cutoff()).stream()
                .map(MessageService::toDto)
                .toList();
    }

    @Transactional
    public MessageDto sendText(String senderUsername, Long toUserId, String content) {
        validateContent(content);
        User sender = requireUser(senderUsername);
        User recipient = requireRecipient(sender, toUserId);
        Message saved = messageRepository.save(new Message(sender, recipient, content.trim()));
        return deliver(saved, List.of(recipient, sender));
    }

    @Transactional
    public MessageDto sendImage(String senderUsername, Long toUserId, String contentType, byte[] bytes) {
        validateImage(contentType, bytes);
        User sender = requireUser(senderUsername);
        User recipient = requireRecipient(sender, toUserId);
        Message saved = messageRepository.save(new Message(sender, recipient, Message.Type.IMAGE, ""));
        imageRepository.save(new MessageImage(saved, contentType, bytes));
        return deliver(saved, List.of(recipient, sender));
    }

    // ---------------------------------------------------------------- groups

    /** Last-24h messages in a group the current user belongs to. */
    public List<MessageDto> groupHistory(String username, Long groupId) {
        requireMembership(requireUser(username), groupId);
        return messageRepository.findGroupConversation(groupId, cutoff()).stream()
                .map(MessageService::toDto)
                .toList();
    }

    @Transactional
    public MessageDto sendGroupText(String senderUsername, Long groupId, String content) {
        validateContent(content);
        User sender = requireUser(senderUsername);
        GroupChat group = requireGroup(groupId);
        requireMembership(sender, groupId);
        Message saved = messageRepository.save(new Message(sender, group, Message.Type.TEXT, content.trim()));
        return deliver(saved, membersOf(groupId));
    }

    @Transactional
    public MessageDto sendGroupImage(String senderUsername, Long groupId, String contentType, byte[] bytes) {
        validateImage(contentType, bytes);
        User sender = requireUser(senderUsername);
        GroupChat group = requireGroup(groupId);
        requireMembership(sender, groupId);
        Message saved = messageRepository.save(new Message(sender, group, Message.Type.IMAGE, ""));
        imageRepository.save(new MessageImage(saved, contentType, bytes));
        return deliver(saved, membersOf(groupId));
    }

    // ---------------------------------------------------------------- images

    /** Image bytes, only for a participant/member and only while the message is alive. */
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

    private void validateContent(String content) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must be 1-2000 characters");
        }
    }

    private void validateImage(String contentType, byte[] bytes) {
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only PNG, JPEG, GIF or WebP images are allowed");
        }
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image");
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

    /** Push the saved message to every target's queue (sender included for echo). */
    private MessageDto deliver(Message saved, List<User> targets) {
        MessageDto dto = toDto(saved);
        for (User target : targets) {
            messagingTemplate.convertAndSendToUser(target.getUsername(), "/queue/messages", dto);
        }
        return dto;
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

    private static MessageDto toDto(Message m) {
        return new MessageDto(m.getId(), m.getType().name(),
                m.getGroup() != null ? m.getGroup().getId() : null,
                m.getSender().getId(),
                m.getRecipient() != null ? m.getRecipient().getId() : null,
                m.getSender().getUsername(), m.getContent(), m.getCreatedAt());
    }
}

package com.chatapp.backend.message;

import com.chatapp.backend.contact.Contact;
import com.chatapp.backend.contact.ContactRepository;
import com.chatapp.backend.user.User;
import com.chatapp.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ContactRepository contactRepository;
    private final UserRepository userRepository;
    private final Duration ttl;

    public MessageService(MessageRepository messageRepository, ContactRepository contactRepository,
                          UserRepository userRepository,
                          @Value("${app.messages.ttl-hours}") long ttlHours) {
        this.messageRepository = messageRepository;
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public record MessageDto(Long id, Long senderId, Long recipientId,
                             String senderUsername, String content, Instant createdAt) {}

    /** Last-24h conversation between the current user and a contact. */
    public List<MessageDto> history(String username, Long otherUserId) {
        User me = requireUser(username);
        User other = userRepository.findById(otherUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        requireContact(me, other);
        return messageRepository.findConversation(me, other, Instant.now().minus(ttl)).stream()
                .map(MessageService::toDto)
                .toList();
    }

    @Transactional
    public MessageDto send(String senderUsername, Long toUserId, String content) {
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must be 1-2000 characters");
        }
        User sender = requireUser(senderUsername);
        User recipient = userRepository.findById(toUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient not found"));
        requireContact(sender, recipient);
        Message saved = messageRepository.save(new Message(sender, recipient, content.trim()));
        return toDto(saved);
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
        return new MessageDto(m.getId(), m.getSender().getId(), m.getRecipient().getId(),
                m.getSender().getUsername(), m.getContent(), m.getCreatedAt());
    }
}

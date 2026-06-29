package com.chatapp.backend.message;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/**
 * STOMP entry point. Clients SEND to /app/chat; MessageService persists and
 * pushes the saved message to both participants' /user/queue/messages.
 */
@Controller
public class ChatController {

    private final MessageService messageService;

    public ChatController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Either toUserId (DM) or groupId must be set. For groups, {@code content} is
     * the encrypted blob and {@code envelopes} maps each member's userId to their
     * wrapped content key. DM text rides entirely in {@code content}.
     */
    public record SendMessageRequest(Long toUserId, Long groupId, String content,
                                     Map<Long, String> envelopes) {}

    @MessageMapping("/chat")
    public void send(@Payload SendMessageRequest req, Principal principal) {
        if (req.groupId() != null) {
            messageService.sendGroupText(principal.getName(), req.groupId(), req.content(), req.envelopes());
        } else {
            messageService.sendText(principal.getName(), req.toUserId(), req.content());
        }
    }
}

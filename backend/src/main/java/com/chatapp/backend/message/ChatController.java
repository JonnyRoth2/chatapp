package com.chatapp.backend.message;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

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

    /** Either toUserId (DM) or groupId must be set. */
    public record SendMessageRequest(Long toUserId, Long groupId, String content) {}

    @MessageMapping("/chat")
    public void send(@Payload SendMessageRequest req, Principal principal) {
        if (req.groupId() != null) {
            messageService.sendGroupText(principal.getName(), req.groupId(), req.content());
        } else {
            messageService.sendText(principal.getName(), req.toUserId(), req.content());
        }
    }
}

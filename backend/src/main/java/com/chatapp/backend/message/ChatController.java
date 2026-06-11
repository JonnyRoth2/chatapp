package com.chatapp.backend.message;

import com.chatapp.backend.user.UserRepository;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * STOMP entry point. Clients SEND to /app/chat; the saved message is pushed
 * to both participants' /user/queue/messages subscriptions.
 */
@Controller
public class ChatController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    public ChatController(MessageService messageService, SimpMessagingTemplate messagingTemplate,
                          UserRepository userRepository) {
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
        this.userRepository = userRepository;
    }

    public record SendMessageRequest(Long toUserId, String content) {}

    @MessageMapping("/chat")
    public void send(@Payload SendMessageRequest req, Principal principal) {
        MessageService.MessageDto saved = messageService.send(principal.getName(), req.toUserId(), req.content());
        String recipientUsername = userRepository.findById(req.toUserId()).orElseThrow().getUsername();
        messagingTemplate.convertAndSendToUser(recipientUsername, "/queue/messages", saved);
        // echo to the sender so all their open tabs/devices stay in sync
        messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/messages", saved);
    }
}

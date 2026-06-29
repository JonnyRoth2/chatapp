package com.chatapp.backend.message;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/{userId}")
    public List<MessageService.MessageDto> history(@PathVariable Long userId, Principal principal) {
        return messageService.history(principal.getName(), userId);
    }

    /** Encrypted DM image: {@code file} is the ciphertext, {@code envelope} the
     *  recipient's wrapped content key. */
    @PostMapping("/{userId}/image")
    public MessageService.MessageDto sendImage(@PathVariable Long userId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam("envelope") String envelope,
                                               Principal principal) {
        try {
            return messageService.sendImage(principal.getName(), userId,
                    file.getBytes(), Map.of(userId, envelope));
        } catch (IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Could not read uploaded file");
        }
    }

    @GetMapping("/image/{messageId}")
    public ResponseEntity<byte[]> image(@PathVariable Long messageId, Principal principal) {
        MessageService.ImageData img = messageService.image(principal.getName(), messageId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM) // opaque ciphertext; client decrypts
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=3600")
                .body(img.bytes());
    }
}

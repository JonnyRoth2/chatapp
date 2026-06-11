package com.chatapp.backend.contact;

import com.chatapp.backend.user.User;
import com.chatapp.backend.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactRepository contactRepository;
    private final UserRepository userRepository;

    public ContactController(ContactRepository contactRepository, UserRepository userRepository) {
        this.contactRepository = contactRepository;
        this.userRepository = userRepository;
    }

    public record ContactDto(Long userId, String username) {}
    public record AddContactRequest(@NotBlank String additionKey) {}

    @GetMapping
    public List<ContactDto> list(Principal principal) {
        User me = currentUser(principal);
        return contactRepository.findAllForUser(me).stream()
                .map(c -> toDto(c, me))
                .toList();
    }

    @PostMapping
    public ContactDto add(@Valid @RequestBody AddContactRequest req, Principal principal) {
        User me = currentUser(principal);
        User other = userRepository.findByAdditionKey(req.additionKey().trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user with that addition key"));
        if (other.getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You can't add yourself");
        }
        // check both orders via the normalized pair
        Contact normalized = new Contact(me, other);
        if (contactRepository.existsPair(normalized.getUserA(), normalized.getUserB())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already in your contacts");
        }
        contactRepository.save(normalized);
        return toDto(normalized, me);
    }

    private ContactDto toDto(Contact c, User me) {
        User other = c.other(me);
        return new ContactDto(other.getId(), other.getUsername());
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}

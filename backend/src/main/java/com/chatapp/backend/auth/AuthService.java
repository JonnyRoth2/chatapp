package com.chatapp.backend.auth;

import com.chatapp.backend.security.JwtService;
import com.chatapp.backend.user.User;
import com.chatapp.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;

@Service
public class AuthService {

    // no 0/O/1/I — addition keys get read aloud and typed by hand
    private static final char[] KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int KEY_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        User user = new User(username, passwordEncoder.encode(password), generateUniqueKey());
        userRepository.save(user);
        return toResponse(user);
    }

    public AuthResponse login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return toResponse(user);
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(jwtService.generateToken(user.getUsername()),
                user.getId(), user.getUsername(), user.getAdditionKey());
    }

    private String generateUniqueKey() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(KEY_LENGTH);
            for (int i = 0; i < KEY_LENGTH; i++) {
                sb.append(KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)]);
            }
            String key = sb.toString();
            if (!userRepository.existsByAdditionKey(key)) {
                return key;
            }
        }
        throw new IllegalStateException("Could not generate a unique addition key");
    }

    public record AuthResponse(String token, Long userId, String username, String additionKey) {}
}

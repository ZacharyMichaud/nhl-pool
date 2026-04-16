package com.nhlpool.service;

import com.nhlpool.config.JwtUtil;
import com.nhlpool.domain.Role;
import com.nhlpool.domain.User;
import com.nhlpool.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${app.admin.secret}")
    private String adminSecret;

    public record RegisterRequest(String username, String password, String displayName, String adminSecret) {}
    public record LoginRequest(String username, String password) {}
    public record AuthResponse(String token, Long userId, String username, String displayName, String role, Long teamId) {}

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("Username already taken");
        }

        Role role = (request.adminSecret() != null && request.adminSecret().equals(adminSecret))
                ? Role.ADMIN : Role.USER;

        User user = User.builder()
                .username(request.username())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName())
                .role(role)
                .build();

        user = userRepository.save(user);
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole().name());

        return new AuthResponse(token, user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole().name(), user.getTeam() != null ? user.getTeam().getId() : null);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole().name());
        return new AuthResponse(token, user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole().name(), user.getTeam() != null ? user.getTeam().getId() : null);
    }
}

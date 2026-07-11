package com.edutrack.controller;

import com.edutrack.dto.AuthRequest;
import com.edutrack.dto.AuthResponse;
import com.edutrack.model.User;
import com.edutrack.repository.UserRepository;
import com.edutrack.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepo;

    public AuthController(AuthenticationManager authManager, JwtUtil jwtUtil, UserRepository userRepo) {
        this.authManager = authManager;
        this.jwtUtil     = jwtUtil;
        this.userRepo    = userRepo;
    }

    /**
     * POST /api/auth/login
     * Body: { "email": "...", "password": "..." }
     * Returns: JWT token + user info
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = (User) auth.getPrincipal();

        // Update last login timestamp
        user.setLastLogin(LocalDateTime.now());
        userRepo.save(user);

        String token = jwtUtil.generateToken(user, user.getRole().name());

        return ResponseEntity.ok(new AuthResponse(
                token,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        ));
    }

    /**
     * GET /api/auth/me — returns current user info from existing token.
     *
     * BUG FIX #8: The original code generated a fresh JWT token on every /me call,
     * wasting CPU and making token lifecycle untrackable.
     * Now we just return the user info; the client keeps their existing token.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(Authentication authentication) {
        User user = (User) authentication.getPrincipal();
        // Return user info without re-generating a token
        return ResponseEntity.ok(new AuthResponse(
                null,   // token is null — client already has it
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        ));
    }
}

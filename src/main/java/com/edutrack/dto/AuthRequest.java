package com.edutrack.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// ── Auth ──────────────────────────────────────────────────
public record AuthRequest(
        @Email @NotBlank String email,
        @NotBlank String password
) {}

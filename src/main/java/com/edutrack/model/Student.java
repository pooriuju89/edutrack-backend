package com.edutrack.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "students")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Student ID is required")
    @Size(max = 50, message = "Student ID must be 50 characters or less")
    @Column(nullable = false, unique = true)
    private String studentId;          // e.g. "MC2024-0042"

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must be 100 characters or less")
    @Column(nullable = false)
    private String fullName;

    @NotBlank(message = "Grade is required")
    @Size(max = 20, message = "Grade must be 20 characters or less")
    @Column(nullable = false)
    private String grade;              // e.g. "10A"

    @NotBlank(message = "Parent email is required")
    @Email(message = "Parent email must be a valid email address")
    @Column(nullable = false)
    private String parentEmail;

    @Size(max = 20, message = "Parent phone must be 20 characters or less")
    private String parentPhone;

    @Column(nullable = false)
    private String qrCode;             // unique token used in QR image (set by server)

    // FIX: Was missing @Column(columnDefinition = "TEXT") — defaulted to varchar(255),
    // but a base64-encoded QR PNG is typically 2,000-5,000+ characters. This caused
    // Hibernate's schema migration to FAIL on every app restart with:
    // "ERROR: value too long for type character varying(255)" because existing rows
    // already had base64 strings longer than 255 chars.
    @Column(columnDefinition = "TEXT")
    private String qrImageBase64;      // plain Base64 PNG (no data-URI prefix)

    @Column(columnDefinition = "TEXT")
    private String photoBase64;        // DEPRECATED — kept for migration compatibility only

    // FIX: Cloudinary CDN URL for student photo.
    // Upload happens on the frontend via Cloudinary unsigned upload preset.
    // The URL (e.g. https://res.cloudinary.com/xxx/image/upload/...) is stored here.
    // Scan response returns this URL directly — no more massive base64 in API responses.
    @Column(length = 500)
    private String photoUrl;

    @Size(max = 50, message = "Registration number must be 50 characters or less")
    private String registrationNumber; // e.g. "REG-2024-001"

    private LocalDate dateOfBirth;

    @Column(nullable = false)
    private boolean active = true;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}

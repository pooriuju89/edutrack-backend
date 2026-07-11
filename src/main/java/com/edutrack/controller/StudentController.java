package com.edutrack.controller;

import com.edutrack.model.Student;
import com.edutrack.repository.StudentRepository;
import com.edutrack.service.QrCodeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/students")
public class StudentController {

    private final StudentRepository studentRepo;
    private final QrCodeService qrCodeService;

    public StudentController(StudentRepository studentRepo, QrCodeService qrCodeService) {
        this.studentRepo = studentRepo;
        this.qrCodeService = qrCodeService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<List<Student>> all() {
        return ResponseEntity.ok(studentRepo.findByActiveTrue());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<Student> getById(@PathVariable Long id) {
        return studentRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Student> create(@Valid @RequestBody Student student) {
        // FIX: Check for duplicate studentId before saving to prevent double-registration
        // if the frontend accidentally fires the request twice (e.g. double-click submit).
        if (studentRepo.findByStudentId(student.getStudentId()).isPresent()) {
            return ResponseEntity.badRequest().build();
        }

        // Server assigns the QR token — client must not send one
        String qrToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        student.setQrCode(qrToken);

        try {
            String base64 = qrCodeService.generateQrBase64(qrToken, 250);
            student.setQrImageBase64(base64);
        } catch (Exception e) {
            // Non-fatal: QR image can be regenerated later via the regenerate-qr endpoint
        }

        // photoUrl comes from frontend Cloudinary upload — photoBase64 is no longer used
        // student.getPhotoUrl() is already set from the request body if provided

        return ResponseEntity.ok(studentRepo.save(student));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Student> update(@PathVariable Long id,
                                          @Valid @RequestBody Student updated) {
        return studentRepo.findById(id).map(s -> {
            s.setFullName(updated.getFullName());
            s.setGrade(updated.getGrade());
            s.setParentEmail(updated.getParentEmail());
            s.setParentPhone(updated.getParentPhone());
            s.setDateOfBirth(updated.getDateOfBirth());
            if (updated.getRegistrationNumber() != null) s.setRegistrationNumber(updated.getRegistrationNumber());
            if (updated.getPhotoBase64() != null) s.setPhotoBase64(updated.getPhotoBase64());
            if (updated.getPhotoUrl() != null)     s.setPhotoUrl(updated.getPhotoUrl());
            return ResponseEntity.ok(studentRepo.save(s));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        studentRepo.findById(id).ifPresent(s -> {
            s.setActive(false);
            studentRepo.save(s);
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/students/{id}/regenerate-qr
     * Generates a new QR token for the student (old cards stop working immediately).
     */
    @PostMapping("/{id}/regenerate-qr")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Student> regenerateQr(@PathVariable Long id) {
        Student s = studentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        String newToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        s.setQrCode(newToken);

        try {
            s.setQrImageBase64(qrCodeService.generateQrBase64(newToken, 250));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate QR image: " + e.getMessage(), e);
        }

        return ResponseEntity.ok(studentRepo.save(s));
    }
}

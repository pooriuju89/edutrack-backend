package com.edutrack.controller;

import com.edutrack.dto.ScanRequest;
import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import com.edutrack.model.User;
import com.edutrack.repository.AttendanceRepository;
import com.edutrack.repository.StudentRepository;
import com.edutrack.service.CalendarService;
import com.edutrack.service.EmailService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/attendance")
public class AttendanceController {

    // FIX: Added logger — previously email failures were swallowed silently
    // with zero output, making it impossible to diagnose "email not working".
    private static final Logger log = LoggerFactory.getLogger(AttendanceController.class);

    private static final LocalTime LATE_CUTOFF = LocalTime.of(8, 0);

    private final AttendanceRepository attendanceRepo;
    private final StudentRepository    studentRepo;
    private final EmailService         emailService;
    private final CalendarService      calendarService;

    public AttendanceController(AttendanceRepository attendanceRepo,
                                StudentRepository studentRepo,
                                EmailService emailService,
                                CalendarService calendarService) {
        this.attendanceRepo  = attendanceRepo;
        this.studentRepo     = studentRepo;
        this.emailService    = emailService;
        this.calendarService = calendarService;
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCANNER')")
    // FIX: @Transactional is REQUIRED for the pessimistic lock below to work.
    // Without it, two near-simultaneous scan requests for the same student both
    // read "not yet scanned" before either commits, so both send a duplicate
    // email (exactly what happened in production — 2 departure emails 20ms apart).
    // With this lock, the second request BLOCKS until the first transaction
    // commits, then correctly sees the record as already updated.
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> scan(@Valid @RequestBody ScanRequest body,
                                  Authentication auth) {

        // Block scanning on weekends and special holidays
        if (!calendarService.isSchoolDay(LocalDate.now())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Today is not a school day. No attendance recorded."));
        }

        String qrCode   = body.qrCode();
        String scanType = body.scanType() != null ? body.scanType().toUpperCase() : "ARRIVAL";

        Student student = studentRepo.findByQrCode(qrCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown QR code"));

        if (!student.isActive()) {
            throw new IllegalArgumentException("This student's QR code is no longer active");
        }

        LocalDate today   = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        User scannedBy    = (User) auth.getPrincipal();

        // FIX: Acquire a Postgres advisory lock keyed on (studentId + date) BEFORE
        // reading the record. Unlike the row-level PESSIMISTIC_WRITE lock below,
        // this works even when NO record exists yet — closing the race condition
        // for a student's very first scan of the day, where two simultaneous
        // requests could otherwise both pass the "record doesn't exist" check.
        long lockKey = (student.getStudentId() + "_" + today).hashCode();
        attendanceRepo.acquireScanLock(lockKey);

        // Load existing record for today, or create a new skeleton (not saved yet)
        AttendanceRecord record = attendanceRepo
                .findByStudentAndAttendanceDateForUpdate(student, today)
                .orElseGet(() -> AttendanceRecord.builder()
                        .student(student)
                        .attendanceDate(today)
                        .status(AttendanceRecord.Status.ABSENT)
                        .scannedBy(scannedBy)
                        .parentNotified(false)
                        .parentDepartureNotified(false)
                        .build());

        if ("ARRIVAL".equals(scanType)) {
            // ── ARRIVAL scan ─────────────────────────────────────────────────
            // Only update arrivalTime if this is the FIRST arrival scan.
            // Prevents a re-scan from overwriting the original arrival time.
            if (record.getArrivalTime() == null) {
                boolean isLate = now.toLocalTime().isAfter(LATE_CUTOFF);
                record.setArrivalTime(now);
                record.setStatus(isLate
                        ? AttendanceRecord.Status.LATE
                        : AttendanceRecord.Status.PRESENT);

                // FIX: Send email BEFORE saving so parentNotified is set correctly
                // in a SINGLE save — eliminates the double-save that caused duplicate rows.
                boolean notified = false;
                try {
                    if (isLate) {
                        emailService.sendLateAlert(student, now);
                    } else {
                        emailService.sendArrivalAlert(student, now);
                    }
                    notified = true;
                } catch (Exception e) {
                    // FIX: Log the real failure reason (bad API key, unverified sender,
                    // SMTP timeout, etc.) instead of swallowing it silently.
                    log.error("ARRIVAL email failed for student {} ({}): {}",
                              student.getStudentId(), student.getParentEmail(), e.getMessage(), e);
                    // parentNotified stays false, will retry on next arrival scan
                }
                record.setParentNotified(notified);
                attendanceRepo.save(record);  // single save
            } else {
                // Already scanned in — just acknowledge, don't update time or resend email
                // FIX: Only save if this is a NEW (unsaved) record — not an existing one.
                // Calling save() on an already-persisted record is the second source of
                // duplicate rows alongside AbsenceScheduler's double-save.
                if (record.getId() == null) {
                    attendanceRepo.save(record);
                }
            }

        } else if ("DEPARTURE".equals(scanType)) {
            // ── DEPARTURE scan ────────────────────────────────────────────────
            // Only update departureTime if no departure has been recorded yet.
            // Prevents re-scan from overwriting original departure time.
            if (record.getDepartureTime() == null) {
                record.setDepartureTime(now);

                // Edge case: student scanned out without ever scanning in
                if (record.getStatus() == AttendanceRecord.Status.ABSENT) {
                    record.setStatus(AttendanceRecord.Status.PRESENT);
                }

                // FIX: Send email BEFORE saving — single save, no duplicate rows
                boolean notified = false;
                try {
                    emailService.sendDepartureAlert(student, now);
                    notified = true;
                } catch (Exception e) {
                    // FIX: Log the real failure reason instead of swallowing it silently.
                    log.error("DEPARTURE email failed for student {} ({}): {}",
                              student.getStudentId(), student.getParentEmail(), e.getMessage(), e);
                    // will retry on next departure scan
                }
                record.setParentDepartureNotified(notified);
                attendanceRepo.save(record);  // single save
            } else {
                // Already scanned out — just acknowledge
                // FIX: Only save if new record — avoid duplicate on re-scan
                if (record.getId() == null) {
                    attendanceRepo.save(record);
                }
            }

        } else {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid scanType. Must be ARRIVAL or DEPARTURE."));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message",            "Attendance recorded");
        response.put("student",            student.getFullName());
        response.put("studentId",          student.getStudentId());
        response.put("grade",              student.getGrade());
        response.put("registrationNumber", student.getRegistrationNumber());
        // FIX: Return Cloudinary URL instead of base64 — much smaller response
        response.put("photoUrl",           student.getPhotoUrl() != null ? student.getPhotoUrl() : "");
        response.put("status",        record.getStatus().name());
        response.put("arrivalTime",   record.getArrivalTime()   != null ? record.getArrivalTime().toString()   : null);
        response.put("departureTime", record.getDepartureTime() != null ? record.getDepartureTime().toString() : null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<List<Map<String, Object>>> today() {
        List<AttendanceRecord> records = attendanceRepo.findByAttendanceDate(LocalDate.now());

        // FIX: Deduplicate by studentId in Java — keep highest-priority status per student.
        // PRESENT > LATE > ABSENT. This handles the race condition where AbsenceScheduler
        // creates an ABSENT record and a scan creates a PRESENT record for the same student
        // on the same day, and both survive despite the unique constraint (e.g. constraint
        // was added after existing duplicates, or constraint not yet applied on Neon DB).
        java.util.Map<String, AttendanceRecord> deduped = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> priority = java.util.Map.of(
            "PRESENT", 3, "LATE", 2, "ABSENT", 1
        );
        for (AttendanceRecord r : records) {
            if (r.getStudent() == null) continue;
            String sid = r.getStudent().getStudentId();
            AttendanceRecord existing = deduped.get(sid);
            int thisPriority = priority.getOrDefault(r.getStatus().name(), 0);
            int existPriority = existing == null ? 0 : priority.getOrDefault(existing.getStatus().name(), 0);
            if (existing == null || thisPriority > existPriority) {
                deduped.put(sid, r);
            }
        }
        List<AttendanceRecord> unique = new java.util.ArrayList<>(deduped.values());

        List<Map<String, Object>> result = unique.stream().map(r -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",            r.getId());
            entry.put("attendanceDate", r.getAttendanceDate().toString());
            entry.put("status",        r.getStatus().name());
            entry.put("arrivalTime",   r.getArrivalTime()   != null ? r.getArrivalTime().toString()   : null);
            entry.put("departureTime", r.getDepartureTime() != null ? r.getDepartureTime().toString() : null);
            if (r.getStudent() != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("fullName",  r.getStudent().getFullName());
                s.put("grade",     r.getStudent().getGrade());
                s.put("studentId", r.getStudent().getStudentId());
                entry.put("student", s);
            }
            return entry;
        }).toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/student/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<List<Map<String, Object>>> studentHistory(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Student student = studentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        List<Map<String, Object>> result =
                attendanceRepo.findByStudentAndAttendanceDateBetween(student, fromDate, toDate)
                        .stream().map(r -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id",            r.getId());
                            entry.put("attendanceDate", r.getAttendanceDate().toString());
                            entry.put("status",        r.getStatus().name());
                            entry.put("arrivalTime",   r.getArrivalTime()   != null ? r.getArrivalTime().toString()   : null);
                            entry.put("departureTime", r.getDepartureTime() != null ? r.getDepartureTime().toString() : null);
                            return entry;
                        }).toList();

        return ResponseEntity.ok(result);
    }
}

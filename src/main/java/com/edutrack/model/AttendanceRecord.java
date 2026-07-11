package com.edutrack.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendance_records",
       indexes = {
           @Index(name = "idx_att_student_date", columnList = "student_id, attendance_date"),
           @Index(name = "idx_att_date",         columnList = "attendance_date")
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    // Prevent Jackson from trying to serialize the full Student graph recursively
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "attendanceRecords"})
    private Student student;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private LocalDateTime arrivalTime;

    private LocalDateTime departureTime;

    /** Which user performed the scan — excluded from JSON to avoid exposing user data */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scanned_by")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password", "authorities"})
    private User scannedBy;

    /** True after the arrival/absent/late email has been sent to the parent */
    private boolean parentNotified = false;

    /** True after the departure email has been sent to the parent */
    private boolean parentDepartureNotified = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }

    public enum Status {
        PRESENT, LATE, ABSENT
    }
}

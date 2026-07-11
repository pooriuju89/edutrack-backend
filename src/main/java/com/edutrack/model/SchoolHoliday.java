package com.edutrack.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "school_holidays",
       indexes = @Index(name = "idx_holiday_date", columnList = "holiday_date", unique = true))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolHoliday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Holiday date is required")
    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate holidayDate;

    @NotBlank(message = "Reason is required")
    @Column(nullable = false)
    private String reason;          // e.g. "Vesak Day", "Term Break Day 1"

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { this.createdAt = LocalDateTime.now(); }
}

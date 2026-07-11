package com.edutrack.controller;

import com.edutrack.model.SchoolHoliday;
import com.edutrack.repository.SchoolHolidayRepository;
import com.edutrack.service.CalendarService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/calendar")
public class SchoolCalendarController {

    private final SchoolHolidayRepository holidayRepo;
    private final CalendarService calendarService;

    public SchoolCalendarController(SchoolHolidayRepository holidayRepo,
                                    CalendarService calendarService) {
        this.holidayRepo     = holidayRepo;
        this.calendarService = calendarService;
    }

    // ── Request body ────────────────────────────────────────────────────────────

    record HolidayRequest(
        @NotNull(message = "date is required")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date,

        @NotBlank(message = "reason is required")
        String reason
    ) {}

    // ── Admin: add a holiday ─────────────────────────────────────────────────

    @PostMapping("/holidays")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> addHoliday(@Valid @RequestBody HolidayRequest body) {
        if (holidayRepo.existsByHolidayDate(body.date())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "A holiday already exists for " + body.date()));
        }

        SchoolHoliday holiday = SchoolHoliday.builder()
                .holidayDate(body.date())
                .reason(body.reason())
                .build();
        holidayRepo.save(holiday);

        return ResponseEntity.ok(Map.of(
                "message", "Holiday added",
                "date",    holiday.getHolidayDate().toString(),
                "reason",  holiday.getReason()
        ));
    }

    // ── Admin: remove a holiday ──────────────────────────────────────────────

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeHoliday(@PathVariable Long id) {
        SchoolHoliday holiday = holidayRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Holiday not found"));
        holidayRepo.delete(holiday);
        return ResponseEntity.ok(Map.of("message", "Holiday removed", "date", holiday.getHolidayDate().toString()));
    }

    // ── Calendar view: school days + holidays in a month ─────────────────────
    // Accessible by ADMIN and PRINCIPAL roles
    // GET /api/calendar/month?year=2025&month=6

    @GetMapping("/month")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
    public ResponseEntity<?> getMonthCalendar(
            @RequestParam int year,
            @RequestParam int month) {

        List<LocalDate> schoolDays = calendarService.getSchoolDaysInMonth(year, month);
        List<SchoolHoliday> holidays = calendarService.getHolidaysInMonth(year, month);

        List<Map<String, String>> holidayList = holidays.stream().map(h -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("id",     h.getId().toString());
            entry.put("date",   h.getHolidayDate().toString());
            entry.put("reason", h.getReason());
            return entry;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "year",        year,
                "month",       month,
                "schoolDays",  schoolDays.stream().map(LocalDate::toString).toList(),
                "holidays",    holidayList,
                "totalSchoolDays", schoolDays.size()
        ));
    }

    // ── Quick check: is today a school day? ──────────────────────────────────

    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'SCANNER')")
    public ResponseEntity<?> isTodaySchoolDay() {
        LocalDate today = LocalDate.now();
        boolean isSchoolDay = calendarService.isSchoolDay(today);
        return ResponseEntity.ok(Map.of(
                "date",        today.toString(),
                "isSchoolDay", isSchoolDay
        ));
    }
}

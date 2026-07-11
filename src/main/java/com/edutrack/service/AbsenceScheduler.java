package com.edutrack.service;

import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import com.edutrack.repository.AttendanceRepository;
import com.edutrack.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AbsenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(AbsenceScheduler.class);

    private final StudentRepository    studentRepo;
    private final AttendanceRepository attendanceRepo;
    private final EmailService         emailService;
    private final CalendarService      calendarService;

    public AbsenceScheduler(StudentRepository studentRepo,
                            AttendanceRepository attendanceRepo,
                            EmailService emailService,
                            CalendarService calendarService) {
        this.studentRepo     = studentRepo;
        this.attendanceRepo  = attendanceRepo;
        this.emailService    = emailService;
        this.calendarService = calendarService;
    }

    /**
     * Runs every weekday at 9:00 AM.
     * Skips weekends and special holidays automatically via CalendarService.
     *
     * For every active student with no arrival scan today, this:
     *   1. Creates an ABSENT AttendanceRecord (so it shows in the dashboard)
     *   2. Sends an absence email to the parent
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void notifyAbsentStudents() {
        LocalDate today = LocalDate.now();

        if (!calendarService.isSchoolDay(today)) {
            log.info("AbsenceScheduler: {} is not a school day — skipping.", today);
            return;
        }

        log.info("AbsenceScheduler: checking absences for {}", today);

        List<Student> activeStudents = studentRepo.findByActiveTrue();
        int notified = 0;

        for (Student student : activeStudents) {
            boolean hasRecord = attendanceRepo
                    .findByStudentAndAttendanceDate(student, today)
                    .isPresent();

            if (hasRecord) {
                // Student already has a record (arrived or previously marked) — skip
                continue;
            }

            // FIX: Try sending the email BEFORE saving, so we can set parentNotified
            // correctly in a SINGLE save. The old code did 2 saves (one to create the
            // record, one to set parentNotified=true) — this caused duplicate rows
            // when combined with the unique constraint check race condition.
            boolean notifiedParent = false;
            try {
                emailService.sendAbsentAlert(student);
                notifiedParent = true;
                notified++;
                log.info("Absence email sent for student {}", student.getStudentId());
            } catch (Exception e) {
                log.error("Failed to send absence email for student {}: {}",
                          student.getStudentId(), e.getMessage());
            }

            // Single save with correct parentNotified value already set
            AttendanceRecord record = AttendanceRecord.builder()
                    .student(student)
                    .attendanceDate(today)
                    .status(AttendanceRecord.Status.ABSENT)
                    .parentNotified(notifiedParent)
                    .build();
            attendanceRepo.save(record);
        }

        log.info("AbsenceScheduler: done. {} absence email(s) sent for {}", notified, today);
    }
}

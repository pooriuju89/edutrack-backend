package com.edutrack.controller;

import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import com.edutrack.repository.AttendanceRepository;
import com.edutrack.repository.StudentRepository;
import com.edutrack.service.CalendarService;
import com.edutrack.service.EmailService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.*;

@RestController
@RequestMapping("/analytics")
@PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL')")
public class AnalyticsController {

    private static final LocalTime LATE_CUTOFF = LocalTime.of(8, 0);
    private static final int LATE_CUTOFF_MINUTES = 8 * 60; // 480 minutes from midnight

    private final AttendanceRepository attendanceRepo;
    private final StudentRepository    studentRepo;
    private final CalendarService      calendarService;
    private final EmailService         emailService;

    public AnalyticsController(AttendanceRepository attendanceRepo,
                               StudentRepository studentRepo,
                               CalendarService calendarService,
                               EmailService emailService) {
        this.attendanceRepo  = attendanceRepo;
        this.studentRepo     = studentRepo;
        this.calendarService = calendarService;
        this.emailService    = emailService;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SCHOOL-WIDE ANALYTICS
    // ════════════════════════════════════════════════════════════════════════

    /** GET /api/analytics/summary — today's headline stats
     *  FIX: Allow SCANNER role access so the QR Scanner page can show live stats.
     *  The class-level @PreAuthorize is overridden here for this one endpoint. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'SCANNER')")
    public ResponseEntity<Map<String, Object>> summary() {
        LocalDate today = LocalDate.now();
        long total   = studentRepo.findByActiveTrue().size();
        long present = attendanceRepo.countByDateAndStatusAndActiveStudent(today, AttendanceRecord.Status.PRESENT);
        long late    = attendanceRepo.countByDateAndStatusAndActiveStudent(today, AttendanceRecord.Status.LATE);
        // Absent = students with no scan OR explicitly marked absent
        long absent  = Math.max(total - present - late, 0);
        double pct   = total > 0 ? ((present + late) * 100.0 / total) : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalStudents", total);
        result.put("presentToday",  present);
        result.put("lateToday",     late);
        result.put("absentToday",   absent);
        result.put("attendancePct", round1(pct));
        result.put("date",          today.toString());
        result.put("isSchoolDay",   calendarService.isSchoolDay(today));
        return ResponseEntity.ok(result);
    }

    /** GET /api/analytics/trend?days=10 — daily % for past N days */
    @GetMapping("/trend")
    public ResponseEntity<List<Map<String, Object>>> trend(
            @RequestParam(defaultValue = "10") int days) {

        LocalDate toDate   = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(days - 1);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : attendanceRepo.getDailyTrend(fromDate, toDate)) {
            LocalDate date = (LocalDate) row[0];
            long present   = num(row[1]);
            long late      = num(row[2]);
            long absent    = num(row[3]);
            long total     = present + late + absent;
            double pct     = total > 0 ? ((present + late) * 100.0 / total) : 0;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date",    date.toString());
            entry.put("present", present);
            entry.put("late",    late);
            entry.put("absent",  absent);
            entry.put("pct",     round1(pct));
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /** GET /api/analytics/class-comparison — today's % per grade */
    @GetMapping("/class-comparison")
    public ResponseEntity<List<Map<String, Object>>> classComparison() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : attendanceRepo.getClassAttendanceForDate(LocalDate.now())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("grade", row[0]);
            entry.put("pct",   round1(((Number) row[1]).doubleValue()));
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analytics/high-risk?since=yyyy-MM-dd — students below 80%
     *
     * FIX: Uses countSchoolDays() instead of calendar days to compute attendance %
     * so weekends and holidays don't artificially lower the percentage.
     */
    @GetMapping("/high-risk")
    public ResponseEntity<List<Map<String, Object>>> highRisk(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {

        long totalSchoolDays = calendarService.countSchoolDays(since, LocalDate.now());
        if (totalSchoolDays == 0) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : attendanceRepo.findHighRiskStudentsIncludingUnscanned(since)) {
            Student s     = (Student) row[0];
            long absences = num(row[1]);
            double pct    = ((totalSchoolDays - absences) * 100.0 / totalSchoolDays);
            pct           = Math.max(0, Math.min(100, pct)); // clamp 0–100

            if (pct < 80) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("studentId",     s.getStudentId());
                entry.put("name",          s.getFullName());
                entry.put("grade",         s.getGrade());
                entry.put("absences",      absences);
                entry.put("totalSchoolDays", totalSchoolDays);
                entry.put("attendancePct", round1(pct));
                entry.put("parentEmail",   nvl(s.getParentEmail()));
                result.add(entry);
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analytics/grade-trend?fromDate=&toDate=
     * Per-grade daily breakdown so the principal can compare grades over time.
     */
    @GetMapping("/grade-trend")
    public ResponseEntity<Map<String, Object>> gradeTrend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Map<String, List<Map<String, Object>>> byGrade = new LinkedHashMap<>();

        for (Object[] row : attendanceRepo.getGradeTrendInRange(fromDate, toDate)) {
            String grade   = (String) row[0];
            LocalDate date = (LocalDate) row[1];
            long present   = num(row[2]);
            long late      = num(row[3]);
            long absent    = num(row[4]);
            long total     = present + late + absent;
            double pct     = total > 0 ? ((present + late) * 100.0 / total) : 0;

            byGrade.computeIfAbsent(grade, k -> new ArrayList<>())
                   .add(Map.of("date", date.toString(), "pct", round1(pct)));
        }

        return ResponseEntity.ok(Map.of(
                "fromDate", fromDate.toString(),
                "toDate",   toDate.toString(),
                "grades",   byGrade));
    }

    /** GET /api/analytics/perfect-attendance?fromDate=&toDate= */
    @GetMapping("/perfect-attendance")
    public ResponseEntity<Map<String, Object>> perfectAttendance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        List<Student> stars = attendanceRepo.findPerfectAttendanceStudents(fromDate, toDate);
        List<Map<String, Object>> list = stars.stream().map(s -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("studentId", s.getStudentId());
            e.put("name",      s.getFullName());
            e.put("grade",     s.getGrade());
            return e;
        }).toList();

        return ResponseEntity.ok(Map.of(
                "fromDate", fromDate.toString(),
                "toDate",   toDate.toString(),
                "count",    list.size(),
                "students", list));
    }

    /** GET /api/analytics/most-late?fromDate=&toDate=&limit=10 */
    @GetMapping("/most-late")
    public ResponseEntity<List<Map<String, Object>>> mostLate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "10") int limit) {

        return ResponseEntity.ok(
            attendanceRepo.findMostLateStudents(fromDate, toDate).stream()
                .limit(limit)
                .map(row -> {
                    Student s      = (Student) row[0];
                    long lateCount = num(row[1]);
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("studentId", s.getStudentId());
                    e.put("name",      s.getFullName());
                    e.put("grade",     s.getGrade());
                    e.put("lateCount", lateCount);
                    return e;
                }).toList());
    }

    /**
     * GET /api/analytics/arrival-time?fromDate=&toDate=
     *
     * FIX: Replaced MySQL-specific HOUR()/MINUTE() JPQL functions with Java-side
     * calculation. Fetches raw records with arrivalTime and groups/averages in Java.
     * This works correctly on PostgreSQL (Neon).
     */
    @GetMapping("/arrival-time")
    public ResponseEntity<List<Map<String, Object>>> avgArrivalTime(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        List<AttendanceRecord> records = attendanceRepo.findRecordsWithArrivalTime(fromDate, toDate);

        // Group by grade, compute average arrival minutes from midnight
        Map<String, List<Integer>> gradeMinutes = new LinkedHashMap<>();
        for (AttendanceRecord r : records) {
            String grade  = r.getStudent().getGrade();
            int minutes   = r.getArrivalTime().getHour() * 60 + r.getArrivalTime().getMinute();
            gradeMinutes.computeIfAbsent(grade, k -> new ArrayList<>()).add(minutes);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : gradeMinutes.entrySet()) {
            double avgMinutes = entry.getValue().stream()
                    .mapToInt(Integer::intValue).average().orElse(0);
            int h = (int) (avgMinutes / 60);
            int m = (int) (avgMinutes % 60);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("grade",          entry.getKey());
            e.put("avgArrivalTime", String.format("%02d:%02d", h, m));
            e.put("avgMinutes",     round1(avgMinutes));
            e.put("isLate",         avgMinutes > LATE_CUTOFF_MINUTES);
            result.add(e);
        }

        // Sort by grade name
        result.sort(Comparator.comparing(e -> (String) e.get("grade")));
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/analytics/student/{id}?fromDate=&toDate=
     * Full individual student profile.
     *
     * FIX: attendancePct now uses actual school days in the range, not just
     * recorded days (which only exist after AbsenceScheduler runs).
     * FIX: riskLevel now returns "NO_DATA" when the student has zero records.
     * FIX: removed the dead totalSchoolDays variable that was using only fromDate's month.
     */
    @GetMapping("/student/{id}")
    public ResponseEntity<?> studentProfile(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        Student student = studentRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Student not found"));

        List<AttendanceRecord> records =
                attendanceRepo.findAllForStudentInRange(student, fromDate, toDate);

        // ── Status counts ────────────────────────────────────────────────────
        long presentCount = 0, lateCount = 0, absentCount = 0;
        for (AttendanceRecord r : records) {
            switch (r.getStatus()) {
                case PRESENT -> presentCount++;
                case LATE    -> lateCount++;
                case ABSENT  -> absentCount++;
            }
        }
        long totalRecords = records.size();

        // FIX: Use actual school days in range for an accurate attendance %
        long totalSchoolDays = calendarService.countSchoolDays(fromDate, toDate);

        // If AbsenceScheduler hasn't run yet, some absent days may not have records yet.
        // Use max(totalSchoolDays, totalRecords) as denominator to avoid > 100% values.
        long denominator = Math.max(totalSchoolDays, totalRecords);
        double attendancePct = denominator > 0
                ? ((presentCount + lateCount) * 100.0 / denominator)
                : 0;
        attendancePct = Math.min(100, attendancePct); // clamp

        // ── Calendar heatmap data (date → status) ────────────────────────────
        // Sort by date for consistent frontend rendering
        Map<String, String> heatmap = new LinkedHashMap<>();
        records.stream()
               .sorted(Comparator.comparing(AttendanceRecord::getAttendanceDate))
               .forEach(r -> heatmap.put(r.getAttendanceDate().toString(), r.getStatus().name()));

        // ── Day-of-week pattern ──────────────────────────────────────────────
        Map<String, long[]> dowStats = new LinkedHashMap<>();
        for (DayOfWeek dow : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            dowStats.put(dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH), new long[]{0, 0});
        }
        for (AttendanceRecord r : records) {
            String dayName = r.getAttendanceDate().getDayOfWeek()
                              .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            if (dowStats.containsKey(dayName)) {
                dowStats.get(dayName)[1]++; // total
                if (r.getStatus() == AttendanceRecord.Status.ABSENT) {
                    dowStats.get(dayName)[0]++; // absent
                }
            }
        }
        List<Map<String, Object>> dowPattern = new ArrayList<>();
        for (Map.Entry<String, long[]> e : dowStats.entrySet()) {
            long[] v     = e.getValue();
            double abPct = v[1] > 0 ? (v[0] * 100.0 / v[1]) : 0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day",       e.getKey());
            entry.put("absent",    v[0]);
            entry.put("total",     v[1]);
            entry.put("absentPct", round1(abPct));
            dowPattern.add(entry);
        }

        // ── Current consecutive absent streak (most-recent days first) ───────
        int streak = 0;
        List<AttendanceRecord> reversed = records.stream()
                .sorted(Comparator.comparing(AttendanceRecord::getAttendanceDate).reversed())
                .toList();
        for (AttendanceRecord r : reversed) {
            if (r.getStatus() == AttendanceRecord.Status.ABSENT) streak++;
            else break;
        }

        // ── Average arrival time (Java-side, PostgreSQL-safe) ────────────────
        OptionalDouble avgMinutes = records.stream()
                .filter(r -> r.getArrivalTime() != null)
                .mapToDouble(r -> r.getArrivalTime().getHour() * 60 + r.getArrivalTime().getMinute())
                .average();
        String avgArrival = avgMinutes.isPresent()
                ? String.format("%02d:%02d",
                    (int) (avgMinutes.getAsDouble() / 60),
                    (int) (avgMinutes.getAsDouble() % 60))
                : null;

        // ── Monthly breakdown ────────────────────────────────────────────────
        Map<String, Map<String, Long>> monthly = new LinkedHashMap<>();
        for (AttendanceRecord r : records) {
            String monthKey = r.getAttendanceDate().getYear() + "-"
                    + String.format("%02d", r.getAttendanceDate().getMonthValue());
            monthly.computeIfAbsent(monthKey, k -> {
                Map<String, Long> m = new LinkedHashMap<>();
                m.put("PRESENT", 0L); m.put("LATE", 0L); m.put("ABSENT", 0L);
                return m;
            });
            monthly.get(monthKey).merge(r.getStatus().name(), 1L, Long::sum);
        }

        // ── Risk level ───────────────────────────────────────────────────────
        // FIX: Students with zero records get "NO_DATA" instead of "HIGH_RISK"
        String riskLevel;
        if (totalRecords == 0)        riskLevel = "NO_DATA";
        else if (attendancePct >= 90) riskLevel = "GOOD";
        else if (attendancePct >= 80) riskLevel = "WARNING";
        else                          riskLevel = "HIGH_RISK";

        // ── Build response ───────────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id",          student.getId());
        info.put("studentId",   student.getStudentId());
        info.put("name",        student.getFullName());
        info.put("grade",       student.getGrade());
        info.put("parentEmail", nvl(student.getParentEmail()));
        result.put("student", info);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRecords",    totalRecords);
        stats.put("totalSchoolDays", totalSchoolDays);
        stats.put("present",         presentCount);
        stats.put("late",            lateCount);
        stats.put("absent",          absentCount);
        stats.put("attendancePct",   round1(attendancePct));
        stats.put("absentStreak",    streak);
        stats.put("avgArrivalTime",  avgArrival);
        stats.put("riskLevel",       riskLevel);
        result.put("stats", stats);

        result.put("heatmap",          heatmap);
        result.put("dowPattern",       dowPattern);
        result.put("monthlyBreakdown", monthly);
        result.put("fromDate",         fromDate.toString());
        result.put("toDate",           toDate.toString());

        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/analytics/notify-high-risk?since=yyyy-MM-dd
     * Principal triggers manual warning emails to all high-risk parents.
     *
     * FIX: Uses countSchoolDays() for accurate attendance % (same fix as /high-risk).
     */
    @PostMapping("/notify-high-risk")
    public ResponseEntity<Map<String, Object>> notifyHighRisk(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {

        long totalSchoolDays = calendarService.countSchoolDays(since, LocalDate.now());
        if (totalSchoolDays == 0) {
            return ResponseEntity.ok(Map.of("sent", 0, "failed", 0,
                    "failedStudents", Collections.emptyList(),
                    "message", "No school days in the selected range."));
        }

        List<Object[]> rows = attendanceRepo.findHighRiskStudentsIncludingUnscanned(since);
        int sent = 0, failed = 0;
        List<String> failedStudents = new ArrayList<>();

        for (Object[] row : rows) {
            Student s     = (Student) row[0];
            long absences = num(row[1]);
            double pct    = Math.max(0, Math.min(100,
                    (totalSchoolDays - absences) * 100.0 / totalSchoolDays));

            if (pct < 80) {
                try {
                    emailService.sendAbsenceWarningToParent(s, absences, totalSchoolDays, round1(pct));
                    sent++;
                } catch (Exception e) {
                    failed++;
                    failedStudents.add(s.getStudentId());
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent",           sent);
        result.put("failed",         failed);
        result.put("failedStudents", failedStudents);
        return ResponseEntity.ok(result);
    }

    /** GET /api/analytics/weekly-report — this week Mon–today */
    @GetMapping("/weekly-report")
    public ResponseEntity<Map<String, Object>> weeklyReport() {
        LocalDate today  = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);

        List<Object[]> trend = attendanceRepo.getDailyTrend(monday, today);

        long totalPresent = 0, totalLate = 0, totalAbsent = 0;
        List<Map<String, Object>> days = new ArrayList<>();

        for (Object[] row : trend) {
            LocalDate date = (LocalDate) row[0];
            long p = num(row[1]), l = num(row[2]), a = num(row[3]);
            long t = p + l + a;
            double pct = t > 0 ? ((p + l) * 100.0 / t) : 0;

            totalPresent += p;
            totalLate    += l;
            totalAbsent  += a;

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("date",    date.toString());
            e.put("day",     date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            e.put("present", p);
            e.put("late",    l);
            e.put("absent",  a);
            e.put("pct",     round1(pct));
            days.add(e);
        }

        long grandTotal = totalPresent + totalLate + totalAbsent;
        double weekPct  = grandTotal > 0 ? ((totalPresent + totalLate) * 100.0 / grandTotal) : 0;

        return ResponseEntity.ok(Map.of(
                "weekStart", monday.toString(),
                "weekEnd",   today.toString(),
                "days",      days,
                "totals",    Map.of(
                        "present", totalPresent,
                        "late",    totalLate,
                        "absent",  totalAbsent,
                        "pct",     round1(weekPct))));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private static long   num(Object o)    { return ((Number) o).longValue(); }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static String nvl(String s)    { return s != null ? s : ""; }
}

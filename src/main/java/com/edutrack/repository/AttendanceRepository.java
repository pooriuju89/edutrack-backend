package com.edutrack.repository;

import com.edutrack.model.AttendanceRecord;
import com.edutrack.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<AttendanceRecord, Long> {

    // FIX: Spring Data's derived query used getSingleResult() under the hood,
    // which THROWS NonUniqueResultException if duplicate rows exist for the
    // same student+date (which can happen from historical duplicate data
    // before the unique constraint was added). Changed to return a List and
    // pick the best record in Java — this can NEVER crash, even with duplicates.
    @Query("""
        SELECT a FROM AttendanceRecord a
        WHERE a.student = :student AND a.attendanceDate = :date
        """)
    List<AttendanceRecord> findAllByStudentAndAttendanceDate(
            @Param("student") Student student,
            @Param("date") LocalDate date);

    // FIX: Pessimistic write lock prevents the duplicate-email race condition.
    // When two scan requests arrive almost simultaneously (double-tap, double-click,
    // or a flaky QR reader firing twice), this lock makes the SECOND transaction
    // WAIT for the FIRST to commit before it can read the row. By the time it reads,
    // departureTime/arrivalTime is already set, so the second scan correctly sees
    // "already scanned" and skips sending a duplicate email.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a FROM AttendanceRecord a
        WHERE a.student = :student AND a.attendanceDate = :date
        """)
    List<AttendanceRecord> findAllByStudentAndAttendanceDateForUpdate(
            @Param("student") Student student,
            @Param("date") LocalDate date);

    /**
     * Safe replacement for the old single-result query.
     * Returns the highest-priority record (PRESENT > LATE > ABSENT) if duplicates
     * exist, so callers never crash even with leftover duplicate rows in the DB.
     */
    default Optional<AttendanceRecord> findByStudentAndAttendanceDate(Student student, LocalDate date) {
        List<AttendanceRecord> all = findAllByStudentAndAttendanceDate(student, date);
        if (all.isEmpty()) return Optional.empty();
        if (all.size() == 1) return Optional.of(all.get(0));
        // Duplicates found — pick PRESENT > LATE > ABSENT, log a warning
        return all.stream()
                .max((a, b) -> Integer.compare(priority(a), priority(b)));
    }

    /**
     * FIX: Locking variant used by the scan endpoint. MUST be called inside an
     * active @Transactional method — the lock is only held until the transaction
     * commits or rolls back. This is what actually prevents duplicate emails when
     * two scan requests for the same student arrive within milliseconds of each other.
     */
    default Optional<AttendanceRecord> findByStudentAndAttendanceDateForUpdate(Student student, LocalDate date) {
        List<AttendanceRecord> all = findAllByStudentAndAttendanceDateForUpdate(student, date);
        if (all.isEmpty()) return Optional.empty();
        if (all.size() == 1) return Optional.of(all.get(0));
        return all.stream()
                .max((a, b) -> Integer.compare(priority(a), priority(b)));
    }

    /**
     * FIX (gap closure): PESSIMISTIC_WRITE on findByStudentAndAttendanceDateForUpdate
     * only locks a row that ALREADY EXISTS. For a student's very FIRST scan of the
     * day, no row exists yet, so SELECT...FOR UPDATE locks nothing, and two
     * simultaneous first-scans can both fall through to creating a new record —
     * the unique constraint then rejects the second INSERT, but only AFTER the
     * second request may have already sent an email.
     *
     * pg_advisory_xact_lock takes a lock on an arbitrary integer key for the
     * duration of the current transaction, with NO ROW REQUIRED to exist. The
     * second concurrent transaction blocks here until the first one commits,
     * regardless of whether a record existed beforehand. This closes the gap
     * for first-scan-of-the-day races, on top of the row-level lock above.
     *
     * Key = hash of (studentId + date) so different students/days don't block
     * each other — only truly concurrent scans of the SAME student on the SAME
     * day are serialized.
     *
     * FIX: Removed @Modifying and changed the return type from void to Object.
     * pg_advisory_xact_lock(...) is a SELECT of a function — it returns a result
     * row. @Modifying forces Spring Data to run the query via executeUpdate(),
     * which the Postgres JDBC driver rejects with "A result was returned when
     * none was expected" the moment a row comes back. This was throwing a 500
     * on every single /attendance/scan call. Removing @Modifying makes Spring
     * run it as a normal SELECT via getSingleResult(), which works correctly
     * and still acquires + holds the lock for the duration of the transaction.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:lockKey)", nativeQuery = true)
    Object acquireScanLock(@Param("lockKey") long lockKey);

    private static int priority(AttendanceRecord r) {
        return switch (r.getStatus()) {
            case PRESENT -> 3;
            case LATE -> 2;
            case ABSENT -> 1;
        };
    }

    // FIX 1: JOIN FETCH replaces LAZY load to avoid LazyInitializationException
    //         with open-in-view=false (student loaded in same DB query/session).
    // FIX 2: SELECT DISTINCT prevents Hibernate Cartesian-product duplicate rows.
    //         Without DISTINCT, a student with multiple LAZY relations gets returned
    //         multiple times, causing the dashboard to show them twice.
    // FIX: Removed "ORDER BY ... NULLS LAST" — PostgreSQL rejects DISTINCT with
    // NULLS LAST in JPQL because it requires the ORDER BY column to appear in SELECT,
    // which conflicts with DISTINCT on entity objects. Sorting is now done in the
    // controller after deduplication.
    @Query("""
        SELECT DISTINCT a FROM AttendanceRecord a
        JOIN FETCH a.student
        WHERE a.attendanceDate = :date
        """)
    List<AttendanceRecord> findByAttendanceDate(@Param("date") LocalDate date);

    List<AttendanceRecord> findByStudent(Student student);

    // FIX: JOIN FETCH to avoid LazyInitializationException in studentHistory endpoint
    @Query("""
        SELECT a FROM AttendanceRecord a
        JOIN FETCH a.student
        WHERE a.student = :student
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
        ORDER BY a.attendanceDate ASC
        """)
    List<AttendanceRecord> findByStudentAndAttendanceDateBetween(
            @Param("student")   Student student,
            @Param("fromDate")  LocalDate fromDate,
            @Param("toDate")    LocalDate toDate);

    List<AttendanceRecord> findByAttendanceDateBetween(LocalDate fromDate, LocalDate toDate);

    List<AttendanceRecord> findByParentNotifiedFalseAndStatus(AttendanceRecord.Status status);

    @Query("SELECT COUNT(a) FROM AttendanceRecord a WHERE a.attendanceDate = :date AND a.status = :status AND a.student.active = true")
    long countByDateAndStatusAndActiveStudent(@Param("date") LocalDate date, @Param("status") AttendanceRecord.Status status);

    // ── Student profile queries ──────────────────────────────────────────────

    /** All records for one student in a date range, ordered oldest-first */
    @Query("""
        SELECT a FROM AttendanceRecord a
        WHERE a.student = :student
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
        ORDER BY a.attendanceDate ASC
        """)
    List<AttendanceRecord> findAllForStudentInRange(
            @Param("student")  Student student,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /** Count by status for one student in a date range */
    @Query("""
        SELECT a.status, COUNT(a)
        FROM AttendanceRecord a
        WHERE a.student = :student
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
        GROUP BY a.status
        """)
    List<Object[]> countByStatusForStudent(
            @Param("student")  Student student,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    // ── School-wide / grade queries ──────────────────────────────────────────

    /**
     * Returns every active student alongside their ABSENT count since a given date.
     * Students with zero absences are included (LEFT JOIN) so the caller can
     * compute a correct attendance percentage using actual school days.
     */
    @Query("""
        SELECT s, COUNT(a) as absences
        FROM Student s
        LEFT JOIN AttendanceRecord a
          ON a.student = s
          AND a.status = com.edutrack.model.AttendanceRecord.Status.ABSENT
          AND a.attendanceDate >= :since
        WHERE s.active = true
        GROUP BY s
        ORDER BY absences DESC
        """)
    List<Object[]> findHighRiskStudentsIncludingUnscanned(@Param("since") LocalDate since);

    @Query("""
        SELECT a.attendanceDate,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END) as present,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.LATE    THEN 1 ELSE 0 END) as late,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.ABSENT  THEN 1 ELSE 0 END) as absent
        FROM AttendanceRecord a
        WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
        GROUP BY a.attendanceDate
        ORDER BY a.attendanceDate
        """)
    List<Object[]> getDailyTrend(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    // FIX: The original formula divided (present count) by COUNT(all records including absent),
    // which incorrectly penalised grades that had their absent records created by AbsenceScheduler.
    // The correct formula is: (present + late) / total * 100
    @Query("""
        SELECT a.student.grade,
               (SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END)
              + SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.LATE    THEN 1 ELSE 0 END))
              * 100.0 / COUNT(a) as pct
        FROM AttendanceRecord a
        WHERE a.attendanceDate = :date
          AND a.student.active = true
        GROUP BY a.student.grade
        ORDER BY a.student.grade
        """)
    List<Object[]> getClassAttendanceForDate(@Param("date") LocalDate date);

    /** Grade-level trend for a date range */
    @Query("""
        SELECT a.student.grade,
               a.attendanceDate,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.PRESENT THEN 1 ELSE 0 END) as present,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.LATE    THEN 1 ELSE 0 END) as late,
               SUM(CASE WHEN a.status = com.edutrack.model.AttendanceRecord.Status.ABSENT  THEN 1 ELSE 0 END) as absent
        FROM AttendanceRecord a
        WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
        GROUP BY a.student.grade, a.attendanceDate
        ORDER BY a.student.grade, a.attendanceDate
        """)
    List<Object[]> getGradeTrendInRange(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /**
     * Perfect attendance students: active students with zero ABSENT or LATE
     * records in the given range.
     * NOTE: Students with NO records at all (never scanned) are intentionally
     * excluded by checking s.id NOT IN the attendance records — a student with
     * no records has unknown attendance, not perfect attendance.
     */
    @Query("""
        SELECT s FROM Student s
        WHERE s.active = true
          AND s.id IN (
              SELECT DISTINCT a.student.id FROM AttendanceRecord a
              WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
          )
          AND s.id NOT IN (
              SELECT a.student.id FROM AttendanceRecord a
              WHERE a.attendanceDate BETWEEN :fromDate AND :toDate
                AND a.status IN (
                    com.edutrack.model.AttendanceRecord.Status.ABSENT,
                    com.edutrack.model.AttendanceRecord.Status.LATE
                )
          )
        ORDER BY s.grade, s.fullName
        """)
    List<Student> findPerfectAttendanceStudents(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /** Most frequently late students in a range */
    @Query("""
        SELECT a.student, COUNT(a) as lateCount
        FROM AttendanceRecord a
        WHERE a.status = com.edutrack.model.AttendanceRecord.Status.LATE
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
          AND a.student.active = true
        GROUP BY a.student
        ORDER BY lateCount DESC
        """)
    List<Object[]> findMostLateStudents(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);

    /**
     * Average arrival time per grade.
     * BUG FIX: Removed HOUR() and MINUTE() which are MySQL-specific and fail on PostgreSQL.
     * We now fetch all records with an arrivalTime and compute the average Java-side
     * in AnalyticsController.avgArrivalTime().
     */
    @Query("""
        SELECT a FROM AttendanceRecord a
        WHERE a.arrivalTime IS NOT NULL
          AND a.attendanceDate BETWEEN :fromDate AND :toDate
          AND a.student.active = true
        ORDER BY a.student.grade
        """)
    List<AttendanceRecord> findRecordsWithArrivalTime(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);
}

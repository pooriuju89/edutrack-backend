package com.edutrack.repository;

import com.edutrack.model.SchoolHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolHolidayRepository extends JpaRepository<SchoolHoliday, Long> {

    Optional<SchoolHoliday> findByHolidayDate(LocalDate date);

    boolean existsByHolidayDate(LocalDate date);

    /**
     * All holidays in a given month.
     * FIX: Replaced MySQL-specific YEAR()/MONTH() functions with a BETWEEN range
     * so this works correctly on PostgreSQL (Neon).
     */
    @Query("""
        SELECT h FROM SchoolHoliday h
        WHERE h.holidayDate >= :monthStart
          AND h.holidayDate <= :monthEnd
        ORDER BY h.holidayDate
        """)
    List<SchoolHoliday> findByYearAndMonth(
            @Param("monthStart") LocalDate monthStart,
            @Param("monthEnd")   LocalDate monthEnd);

    /**
     * All holidays within a date range — used by CalendarService.countSchoolDays()
     * to avoid loading the entire holidays table.
     */
    @Query("""
        SELECT h FROM SchoolHoliday h
        WHERE h.holidayDate >= :fromDate
          AND h.holidayDate <= :toDate
        ORDER BY h.holidayDate
        """)
    List<SchoolHoliday> findByDateRange(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate")   LocalDate toDate);
}

package com.edutrack.service;

import com.edutrack.model.SchoolHoliday;
import com.edutrack.repository.SchoolHolidayRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CalendarService {

    private final SchoolHolidayRepository holidayRepo;

    public CalendarService(SchoolHolidayRepository holidayRepo) {
        this.holidayRepo = holidayRepo;
    }

    /**
     * Returns true only if the date is:
     *   - Not a Saturday or Sunday
     *   - Not in the school_holidays table
     */
    public boolean isSchoolDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidayRepo.existsByHolidayDate(date);
    }

    /**
     * Counts actual school days between fromDate and toDate inclusive,
     * excluding weekends and any special holidays in that range.
     * FIX: Now uses findByDateRange() instead of findAll() for efficiency.
     */
    public long countSchoolDays(LocalDate fromDate, LocalDate toDate) {
        Set<LocalDate> holidays = holidayRepo.findByDateRange(fromDate, toDate).stream()
                .map(SchoolHoliday::getHolidayDate)
                .collect(Collectors.toSet());

        return fromDate.datesUntil(toDate.plusDays(1))
                .filter(d -> {
                    DayOfWeek dow = d.getDayOfWeek();
                    return dow != DayOfWeek.SATURDAY
                        && dow != DayOfWeek.SUNDAY
                        && !holidays.contains(d);
                })
                .count();
    }

    /**
     * Returns every day in the given month that is a school day.
     * Used by the frontend calendar to highlight active school days.
     */
    public List<LocalDate> getSchoolDaysInMonth(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate monthStart = ym.atDay(1);
        LocalDate monthEnd   = ym.atEndOfMonth();

        // FIX: Pass LocalDate range instead of year/month ints to avoid
        // the YEAR()/MONTH() PostgreSQL incompatibility in the repository.
        List<SchoolHoliday> holidays = holidayRepo.findByYearAndMonth(monthStart, monthEnd);

        Set<LocalDate> holidayDates = holidays.stream()
                .map(SchoolHoliday::getHolidayDate)
                .collect(Collectors.toSet());

        return monthStart.datesUntil(monthEnd.plusDays(1))
                .filter(d -> {
                    DayOfWeek dow = d.getDayOfWeek();
                    return dow != DayOfWeek.SATURDAY
                        && dow != DayOfWeek.SUNDAY
                        && !holidayDates.contains(d);
                })
                .collect(Collectors.toList());
    }

    /**
     * All special holidays in a month with their reasons — for the admin calendar view.
     */
    public List<SchoolHoliday> getHolidaysInMonth(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return holidayRepo.findByYearAndMonth(ym.atDay(1), ym.atEndOfMonth());
    }
}

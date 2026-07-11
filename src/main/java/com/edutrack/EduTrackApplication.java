package com.edutrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class EduTrackApplication {
    public static void main(String[] args) {
        // FIX: Set JVM default timezone to Sri Lanka Standard Time (UTC+5:30)
        // This ensures LocalDate.now(), LocalDateTime.now(), and all scheduling
        // (AbsenceScheduler cron) run on Sri Lankan time, not the cloud server's UTC.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Colombo"));
        SpringApplication.run(EduTrackApplication.class, args);
    }
}

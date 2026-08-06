package com.example.ping.web;

import com.example.ping.dto.FailuresResponse;
import com.example.ping.dto.StatsResponse;
import com.example.ping.service.PingStatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/pings")
public class PingController {

    private final PingStatsService pingStatsService;

    public PingController(PingStatsService pingStatsService) {
        this.pingStatsService = pingStatsService;
    }

    @GetMapping("/stats")
    public StatsResponse stats(
            @RequestParam(defaultValue = "minute") String granularity,
            @RequestParam(required = false) String date) {
        LocalDate day = (date != null) ? LocalDate.parse(date) : LocalDate.now();
        return pingStatsService.getStats(granularity, day);
    }

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<String> handleBadDate(DateTimeParseException ex) {
        return ResponseEntity.badRequest().body("Invalid date format. Use YYYY-MM-DD.");
    }

    @GetMapping("/failures")
    public FailuresResponse failures() {
        return pingStatsService.getFailures();
    }
}

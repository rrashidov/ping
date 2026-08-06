package com.example.ping.service;

import com.example.ping.domain.PingResult;
import com.example.ping.dto.*;
import com.example.ping.repository.PingRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PingStatsService {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:00");
    private static final DateTimeFormatter HOUR_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00:00");

    private final PingRepository pingRepository;

    public PingStatsService(PingRepository pingRepository) {
        this.pingRepository = pingRepository;
    }

    public StatsResponse getStats(String granularity) {
        return getStats(granularity, LocalDate.now());
    }

    public StatsResponse getStats(String granularity, LocalDate date) {
        boolean byHour = "hour".equalsIgnoreCase(granularity);
        LocalDateTime since = date.atStartOfDay();
        LocalDateTime until = date.plusDays(1).atStartOfDay();

        List<PingResult> results = pingRepository.findBetween(since, until);
        DateTimeFormatter fmt = byHour ? HOUR_FMT : MINUTE_FMT;

        Map<String, List<PingResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(r -> r.getPingedAt().format(fmt)));

        List<StatsBucket> buckets = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<PingResult> rows = e.getValue();
                    long count = rows.size();
                    long failures = rows.stream().filter(r -> !r.isSuccess()).count();
                    OptionalDouble avg = rows.stream()
                            .filter(PingResult::isSuccess)
                            .mapToLong(PingResult::getResponseTimeMs)
                            .average();
                    Double avgMs = avg.isPresent() ? avg.getAsDouble() : null;
                    return new StatsBucket(e.getKey(), avgMs, count, failures);
                })
                .toList();

        return new StatsResponse(byHour ? "hour" : "minute", buckets);
    }

    public FailuresResponse getFailures() {
        List<FailureItem> items = pingRepository.findTop50BySuccessFalseOrderByPingedAtDesc()
                .stream()
                .map(r -> new FailureItem(r.getId(), r.getPingedAt()))
                .toList();
        return new FailuresResponse(items);
    }
}

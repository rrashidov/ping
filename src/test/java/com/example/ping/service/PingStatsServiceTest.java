package com.example.ping.service;

import com.example.ping.domain.PingResult;
import com.example.ping.repository.PingRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PingStatsServiceTest {

    @Test
    void getStats_minute_bucketsLastTwoHours() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDateTime.of(2026, 8, 4, 10, 5, 30);
        var result = new PingResult(t, true, 42L);
        when(repo.findSince(any())).thenReturn(List.of(result));

        var service = new PingStatsService(repo);
        var response = service.getStats("minute");

        assertThat(response.granularity()).isEqualTo("minute");
        assertThat(response.data()).isNotEmpty();
        // the bucket for 10:05 should have avgResponseMs 42.0
        var bucket = response.data().stream()
                .filter(b -> b.bucket().startsWith("2026-08-04T10:05"))
                .findFirst();
        assertThat(bucket).isPresent();
        assertThat(bucket.get().avgResponseMs()).isEqualTo(42.0);
        assertThat(bucket.get().count()).isEqualTo(1);
        assertThat(bucket.get().failures()).isEqualTo(0);
    }

    @Test
    void getStats_hour_bucketsLast48Hours() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDateTime.of(2026, 8, 4, 10, 5, 30);
        var result = new PingResult(t, true, 100L);
        when(repo.findSince(any())).thenReturn(List.of(result));

        var service = new PingStatsService(repo);
        var response = service.getStats("hour");

        assertThat(response.granularity()).isEqualTo("hour");
        var bucket = response.data().stream()
                .filter(b -> b.bucket().startsWith("2026-08-04T10:"))
                .findFirst();
        assertThat(bucket).isPresent();
        assertThat(bucket.get().avgResponseMs()).isEqualTo(100.0);
    }

    @Test
    void getStats_countsBothSuccessAndFailureInBucket() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDateTime.of(2026, 8, 4, 10, 5, 0);
        when(repo.findSince(any())).thenReturn(List.of(
                new PingResult(t, true, 40L),
                new PingResult(t.plusSeconds(10), true, 60L),
                new PingResult(t.plusSeconds(20), false, null)
        ));

        var service = new PingStatsService(repo);
        var response = service.getStats("minute");

        var bucket = response.data().stream()
                .filter(b -> b.bucket().startsWith("2026-08-04T10:05"))
                .findFirst().orElseThrow();
        assertThat(bucket.count()).isEqualTo(3);
        assertThat(bucket.failures()).isEqualTo(1);
        assertThat(bucket.avgResponseMs()).isEqualTo(50.0); // avg of 40 and 60
    }

    @Test
    void getFailures_returnsFailureItems() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDateTime.of(2026, 8, 4, 10, 5, 0);
        var failure = new PingResult(t, false, null);
        // set id via reflection for test purposes — use a spy approach instead
        when(repo.findTop50BySuccessFalseOrderByPingedAtDesc()).thenReturn(List.of(failure));

        var service = new PingStatsService(repo);
        var response = service.getFailures();

        assertThat(response.failures()).hasSize(1);
        assertThat(response.failures().get(0).pingedAt()).isEqualTo(t);
    }
}

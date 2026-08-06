package com.example.ping.service;

import com.example.ping.domain.PingResult;
import com.example.ping.repository.PingRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PingStatsServiceTest {

    @Test
    void getStats_minute_returnsMinuteBucketForToday() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDate.now().atTime(10, 5, 30);
        when(repo.findBetween(any(), any())).thenReturn(List.of(new PingResult(t, true, 42L)));

        var service = new PingStatsService(repo);
        var response = service.getStats("minute", LocalDate.now());

        assertThat(response.granularity()).isEqualTo("minute");
        var bucket = response.data().stream()
                .filter(b -> b.bucket().contains("T10:05:00"))
                .findFirst();
        assertThat(bucket).isPresent();
        assertThat(bucket.get().avgResponseMs()).isEqualTo(42.0);
        assertThat(bucket.get().count()).isEqualTo(1);
        assertThat(bucket.get().failures()).isEqualTo(0);
    }

    @Test
    void getStats_hour_returnsHourBucketForToday() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDate.now().atTime(10, 5, 30);
        when(repo.findBetween(any(), any())).thenReturn(List.of(new PingResult(t, true, 100L)));

        var service = new PingStatsService(repo);
        var response = service.getStats("hour", LocalDate.now());

        assertThat(response.granularity()).isEqualTo("hour");
        var bucket = response.data().stream()
                .filter(b -> b.bucket().contains("T10:00:00"))
                .findFirst();
        assertThat(bucket).isPresent();
        assertThat(bucket.get().avgResponseMs()).isEqualTo(100.0);
    }

    @Test
    void getStats_countsBothSuccessAndFailureInBucket() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDate.now().atTime(10, 5, 0);
        when(repo.findBetween(any(), any())).thenReturn(List.of(
                new PingResult(t, true, 40L),
                new PingResult(t.plusSeconds(10), true, 60L),
                new PingResult(t.plusSeconds(20), false, null)
        ));

        var service = new PingStatsService(repo);
        var response = service.getStats("minute", LocalDate.now());

        var bucket = response.data().stream()
                .filter(b -> b.bucket().contains("T10:05:00"))
                .findFirst().orElseThrow();
        assertThat(bucket.count()).isEqualTo(3);
        assertThat(bucket.failures()).isEqualTo(1);
        assertThat(bucket.avgResponseMs()).isEqualTo(50.0);
    }

    @Test
    void getStats_returnsDataForPastDate() {
        PingRepository repo = mock(PingRepository.class);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        var t = yesterday.atTime(14, 0, 0);
        when(repo.findBetween(
                yesterday.atStartOfDay(),
                yesterday.plusDays(1).atStartOfDay()
        )).thenReturn(List.of(new PingResult(t, true, 77L)));

        var service = new PingStatsService(repo);
        var response = service.getStats("hour", yesterday);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).avgResponseMs()).isEqualTo(77.0);
    }

    @Test
    void getFailures_returnsFailureItems() {
        PingRepository repo = mock(PingRepository.class);
        var t = LocalDate.now().atTime(10, 5, 0);
        when(repo.findTop50BySuccessFalseOrderByPingedAtDesc()).thenReturn(
                List.of(new PingResult(t, false, null)));

        var service = new PingStatsService(repo);
        var response = service.getFailures();

        assertThat(response.failures()).hasSize(1);
        assertThat(response.failures().get(0).pingedAt()).isEqualTo(t);
    }
}

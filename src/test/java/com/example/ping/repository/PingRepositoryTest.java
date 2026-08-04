package com.example.ping.repository;

import com.example.ping.domain.PingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PingRepositoryTest {

    @Autowired
    PingRepository repo;

    @Test
    void savesAndRetrievesResult() {
        var now = LocalDateTime.now();
        repo.save(new PingResult(now, true, 42L));
        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findAll().get(0).getResponseTimeMs()).isEqualTo(42L);
    }

    @Test
    void deleteByPingedAtBefore_removesOldRows() {
        var old = LocalDateTime.now().minusHours(49);
        var recent = LocalDateTime.now();
        repo.save(new PingResult(old, true, 10L));
        repo.save(new PingResult(recent, true, 20L));

        repo.deleteByPingedAtBefore(LocalDateTime.now().minusHours(48));

        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findAll().get(0).getResponseTimeMs()).isEqualTo(20L);
    }

    @Test
    void findTop50BySuccessFalse_returnsOnlyFailures() {
        repo.save(new PingResult(LocalDateTime.now(), true, 10L));
        repo.save(new PingResult(LocalDateTime.now(), false, null));

        List<PingResult> failures = repo.findTop50BySuccessFalseOrderByPingedAtDesc();

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).isSuccess()).isFalse();
    }

    @Test
    void findSince_returnsRowsAfterCutoff() {
        var old = LocalDateTime.now().minusHours(3);
        var recent = LocalDateTime.now().minusMinutes(30);
        repo.save(new PingResult(old, true, 10L));
        repo.save(new PingResult(recent, true, 20L));

        var results = repo.findSince(LocalDateTime.now().minusHours(1));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResponseTimeMs()).isEqualTo(20L);
    }
}

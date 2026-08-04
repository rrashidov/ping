package com.example.ping.scheduler;

import com.example.ping.repository.PingRepository;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class CleanupScheduler {

    private final PingRepository pingRepository;

    public CleanupScheduler(PingRepository pingRepository) {
        this.pingRepository = pingRepository;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanup() {
        pingRepository.deleteByPingedAtBefore(LocalDateTime.now().minusHours(48));
    }
}

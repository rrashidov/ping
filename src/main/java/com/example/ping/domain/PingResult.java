package com.example.ping.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ping_result")
public class PingResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pinged_at", nullable = false)
    private LocalDateTime pingedAt;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "response_time_ms")
    private Long responseTimeMs;

    public PingResult() {}

    public PingResult(LocalDateTime pingedAt, boolean success, Long responseTimeMs) {
        this.pingedAt = pingedAt;
        this.success = success;
        this.responseTimeMs = responseTimeMs;
    }

    public Long getId() { return id; }
    public LocalDateTime getPingedAt() { return pingedAt; }
    public boolean isSuccess() { return success; }
    public Long getResponseTimeMs() { return responseTimeMs; }
}

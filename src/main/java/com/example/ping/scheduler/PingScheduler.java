package com.example.ping.scheduler;

import com.example.ping.domain.PingResult;
import com.example.ping.repository.PingRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class PingScheduler {

    private final PingRepository pingRepository;
    private final HttpClient httpClient;

    public PingScheduler(PingRepository pingRepository, HttpClient httpClient) {
        this.pingRepository = pingRepository;
        this.httpClient = httpClient;
    }

    @Scheduled(fixedDelay = 1000)
    public void ping() {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("https://google.com"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        var start = System.currentTimeMillis();
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            var elapsed = System.currentTimeMillis() - start;
            pingRepository.save(new PingResult(LocalDateTime.now(), true, elapsed));
        } catch (Exception e) {
            pingRepository.save(new PingResult(LocalDateTime.now(), false, null));
        }
    }
}

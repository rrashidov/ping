package com.example.ping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Duration;

@SpringBootApplication
@EnableScheduling
public class PingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PingApplication.class, args);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}

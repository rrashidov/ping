package com.example.ping.scheduler;

import com.example.ping.domain.PingResult;
import com.example.ping.repository.PingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PingSchedulerTest {

    @Test
    void ping_savesSuccessWhenResponseReceived() throws Exception {
        PingRepository repo = mock(PingRepository.class);
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        PingScheduler scheduler = new PingScheduler(repo, httpClient);
        scheduler.ping();

        ArgumentCaptor<PingResult> captor = ArgumentCaptor.forClass(PingResult.class);
        verify(repo).save(captor.capture());
        PingResult saved = captor.getValue();
        assertThat(saved.isSuccess()).isTrue();
        assertThat(saved.getResponseTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(saved.getPingedAt()).isNotNull();
    }

    @Test
    void ping_savesFailureWhenExceptionThrown() throws Exception {
        PingRepository repo = mock(PingRepository.class);
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connection refused"));

        PingScheduler scheduler = new PingScheduler(repo, httpClient);
        scheduler.ping();

        ArgumentCaptor<PingResult> captor = ArgumentCaptor.forClass(PingResult.class);
        verify(repo).save(captor.capture());
        PingResult saved = captor.getValue();
        assertThat(saved.isSuccess()).isFalse();
        assertThat(saved.getResponseTimeMs()).isNull();
    }
}

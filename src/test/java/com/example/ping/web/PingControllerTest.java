package com.example.ping.web;

import com.example.ping.dto.*;
import com.example.ping.service.PingStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PingController.class)
class PingControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PingStatsService pingStatsService;

    @Test
    void statsEndpoint_returnsJson() throws Exception {
        var bucket = new StatsBucket("2026-08-04T10:05:00", 42.0, 58L, 2L);
        when(pingStatsService.getStats("minute", LocalDate.now()))
                .thenReturn(new StatsResponse("minute", List.of(bucket)));

        mvc.perform(get("/api/pings/stats").param("granularity", "minute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("minute"))
                .andExpect(jsonPath("$.data[0].avgResponseMs").value(42.0))
                .andExpect(jsonPath("$.data[0].bucket").value("2026-08-04T10:05:00"));
    }

    @Test
    void statsEndpoint_defaultsToMinute() throws Exception {
        when(pingStatsService.getStats("minute", LocalDate.now()))
                .thenReturn(new StatsResponse("minute", List.of()));

        mvc.perform(get("/api/pings/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("minute"));
    }

    @Test
    void statsEndpoint_acceptsExplicitDate() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(pingStatsService.getStats("hour", yesterday))
                .thenReturn(new StatsResponse("hour", List.of()));

        mvc.perform(get("/api/pings/stats")
                        .param("granularity", "hour")
                        .param("date", yesterday.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void statsEndpoint_returnsBadRequestForInvalidDate() throws Exception {
        mvc.perform(get("/api/pings/stats").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failuresEndpoint_returnsJson() throws Exception {
        var item = new FailureItem(1L, LocalDateTime.of(2026, 8, 4, 10, 5, 32));
        when(pingStatsService.getFailures())
                .thenReturn(new FailuresResponse(List.of(item)));

        mvc.perform(get("/api/pings/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failures[0].id").value(1))
                .andExpect(jsonPath("$.failures").isArray());
    }
}

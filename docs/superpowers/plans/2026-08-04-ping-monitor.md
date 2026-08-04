# Ping Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot + MySQL web app that pings google.com every second, persists results, and displays a live chart of response times and failures.

**Architecture:** Single Spring Boot 3 application serving both the REST API and a Thymeleaf UI. A scheduled job pings google.com via HTTP every second and writes results to MySQL. A second scheduled job cleans up records older than 48 hours. The UI polls two REST endpoints every 5 seconds and updates a Chart.js chart in place.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Flyway, MySQL 8, Thymeleaf, Bootstrap 5 (dark), Chart.js + chartjs-plugin-zoom + hammerjs, Maven, Docker Compose.

## Global Constraints

- Java version: 21
- Spring Boot version: 3.3.x (latest 3.x stable)
- All frontend assets (Bootstrap, Chart.js, hammer.js, chartjs-plugin-zoom) must be bundled locally under `src/main/resources/static` — no CDN URLs anywhere
- Database: MySQL 8, schema managed by Flyway
- Ping target: `https://google.com`, HTTP GET, 5-second timeout
- Success definition: any HTTP response received; only exceptions/timeouts = failure
- Ping interval: every 1 second (`fixedDelay = 1000`)
- Cleanup: hourly cron, deletes rows where `pinged_at < NOW() - 48h`
- Stats granularity `minute`: last 2 hours, bucketed by minute
- Stats granularity `hour`: last 48 hours, bucketed by hour
- Failures endpoint: max 50 results
- UI data refresh: every 5 seconds via `setInterval`
- Docker Compose: two services (`db`, `app`); app waits for db healthcheck

---

## File Structure

```
pet-project-ping/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── src/
│   ├── main/
│   │   ├── java/com/example/ping/
│   │   │   ├── PingApplication.java              # @SpringBootApplication entry point
│   │   │   ├── domain/
│   │   │   │   └── PingResult.java               # JPA entity
│   │   │   ├── repository/
│   │   │   │   └── PingRepository.java           # Spring Data JPA repo + custom queries
│   │   │   ├── scheduler/
│   │   │   │   ├── PingScheduler.java            # @Scheduled ping job
│   │   │   │   └── CleanupScheduler.java         # @Scheduled cleanup job
│   │   │   ├── service/
│   │   │   │   └── PingStatsService.java         # aggregation logic
│   │   │   ├── web/
│   │   │   │   ├── PingController.java           # REST: /api/pings/stats, /api/pings/failures
│   │   │   │   └── UiController.java             # GET / → index.html
│   │   │   └── dto/
│   │   │       ├── StatsBucket.java              # { bucket, avgResponseMs, count, failures }
│   │   │       ├── StatsResponse.java            # { granularity, data: List<StatsBucket> }
│   │   │       ├── FailureItem.java              # { id, pingedAt }
│   │   │       └── FailuresResponse.java         # { failures: List<FailureItem> }
│   │   ├── resources/
│   │   │   ├── application.properties
│   │   │   ├── db/migration/
│   │   │   │   └── V1__create_ping_result.sql    # Flyway migration
│   │   │   ├── templates/
│   │   │   │   └── index.html                    # Thymeleaf page
│   │   │   └── static/
│   │   │       ├── css/
│   │   │       │   └── bootstrap.min.css
│   │   │       └── js/
│   │   │           ├── bootstrap.bundle.min.js
│   │   │           ├── chart.umd.min.js
│   │   │           ├── hammer.min.js
│   │   │           ├── chartjs-plugin-zoom.min.js
│   │   │           └── app.js                    # UI polling + chart logic
│   └── test/
│       └── java/com/example/ping/
│           ├── repository/
│           │   └── PingRepositoryTest.java       # @DataJpaTest
│           ├── service/
│           │   └── PingStatsServiceTest.java     # unit tests
│           ├── web/
│           │   └── PingControllerTest.java       # @WebMvcTest
│           └── scheduler/
│               └── PingSchedulerTest.java        # unit test with mocked HttpClient
```

---

### Task 1: Maven project scaffold + pom.xml

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/ping/PingApplication.java`
- Create: `src/main/resources/application.properties`

**Interfaces:**
- Produces: a compilable Spring Boot project other tasks build on

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.2</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>ping</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>ping</name>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `src/main/java/com/example/ping/PingApplication.java`**

```java
package com.example.ping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PingApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `src/main/resources/application.properties`**

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/pingdb
spring.datasource.username=ping
spring.datasource.password=ping
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

- [ ] **Step 4: Verify the project compiles**

```bash
./mvnw compile
```
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git init
git add pom.xml src/main/java/com/example/ping/PingApplication.java src/main/resources/application.properties
git commit -m "feat: scaffold Spring Boot project"
```

---

### Task 2: Flyway migration + JPA entity + repository

**Files:**
- Create: `src/main/resources/db/migration/V1__create_ping_result.sql`
- Create: `src/main/java/com/example/ping/domain/PingResult.java`
- Create: `src/main/java/com/example/ping/repository/PingRepository.java`
- Create: `src/test/java/com/example/ping/repository/PingRepositoryTest.java`

**Interfaces:**
- Produces:
  - `PingResult` entity with fields: `Long id`, `LocalDateTime pingedAt`, `boolean success`, `Long responseTimeMs`
  - `PingRepository` extending `JpaRepository<PingResult, Long>` with methods:
    - `void deleteByPingedAtBefore(LocalDateTime cutoff)`
    - `List<PingResult> findTop50BySuccessFalseOrderByPingedAtDesc()`

- [ ] **Step 1: Create `src/main/resources/db/migration/V1__create_ping_result.sql`**

```sql
CREATE TABLE ping_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    pinged_at       DATETIME NOT NULL,
    success         BOOLEAN  NOT NULL,
    response_time_ms BIGINT  NULL
);

CREATE INDEX idx_ping_result_pinged_at ON ping_result (pinged_at);
```

- [ ] **Step 2: Create `src/main/java/com/example/ping/domain/PingResult.java`**

```java
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
```

- [ ] **Step 3: Create `src/main/java/com/example/ping/repository/PingRepository.java`**

```java
package com.example.ping.repository;

import com.example.ping.domain.PingResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PingRepository extends JpaRepository<PingResult, Long> {

    @Modifying
    @Query("DELETE FROM PingResult p WHERE p.pingedAt < :cutoff")
    void deleteByPingedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    List<PingResult> findTop50BySuccessFalseOrderByPingedAtDesc();

    @Query("""
        SELECT p FROM PingResult p
        WHERE p.pingedAt >= :since
        ORDER BY p.pingedAt ASC
        """)
    List<PingResult> findSince(@Param("since") LocalDateTime since);
}
```

- [ ] **Step 4: Write the failing tests**

Create `src/test/java/com/example/ping/repository/PingRepositoryTest.java`:

```java
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
```

- [ ] **Step 5: Add test application.properties for H2**

Create `src/test/resources/application-test.properties`:

```properties
spring.datasource.url=jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./mvnw test -pl . -Dtest=PingRepositoryTest
```
Expected: 4 tests pass

- [ ] **Step 7: Commit**

```bash
git add src/
git commit -m "feat: add PingResult entity, repository, and Flyway migration"
```

---

### Task 3: PingScheduler — periodic HTTP ping

**Files:**
- Create: `src/main/java/com/example/ping/scheduler/PingScheduler.java`
- Create: `src/test/java/com/example/ping/scheduler/PingSchedulerTest.java`

**Interfaces:**
- Consumes: `PingRepository.save(PingResult)`
- Produces: `PingScheduler` bean that writes one `PingResult` row per second

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/ping/scheduler/PingSchedulerTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=PingSchedulerTest
```
Expected: FAIL — `PingScheduler` does not exist yet

- [ ] **Step 3: Create `src/main/java/com/example/ping/scheduler/PingScheduler.java`**

```java
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
```

- [ ] **Step 4: Register `HttpClient` as a Spring bean**

Add to `PingApplication.java`:

```java
import org.springframework.context.annotation.Bean;
import java.net.http.HttpClient;
import java.time.Duration;

// inside PingApplication class:
@Bean
public HttpClient httpClient() {
    return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./mvnw test -Dtest=PingSchedulerTest
```
Expected: 2 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add PingScheduler with HTTP ping and persistence"
```

---

### Task 4: CleanupScheduler

**Files:**
- Create: `src/main/java/com/example/ping/scheduler/CleanupScheduler.java`

No separate test needed — the repository `deleteByPingedAtBefore` is already tested in Task 2. This task just wires the scheduler to call it.

- [ ] **Step 1: Create `src/main/java/com/example/ping/scheduler/CleanupScheduler.java`**

```java
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
```

- [ ] **Step 2: Verify project still compiles and existing tests pass**

```bash
./mvnw test
```
Expected: BUILD SUCCESS, all prior tests pass

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/ping/scheduler/CleanupScheduler.java
git commit -m "feat: add hourly cleanup scheduler for records older than 48h"
```

---

### Task 5: PingStatsService + DTOs

**Files:**
- Create: `src/main/java/com/example/ping/dto/StatsBucket.java`
- Create: `src/main/java/com/example/ping/dto/StatsResponse.java`
- Create: `src/main/java/com/example/ping/dto/FailureItem.java`
- Create: `src/main/java/com/example/ping/dto/FailuresResponse.java`
- Create: `src/main/java/com/example/ping/service/PingStatsService.java`
- Create: `src/test/java/com/example/ping/service/PingStatsServiceTest.java`

**Interfaces:**
- Consumes: `PingRepository.findSince(LocalDateTime)`, `PingRepository.findTop50BySuccessFalseOrderByPingedAtDesc()`
- Produces:
  - `PingStatsService.getStats(String granularity)` → `StatsResponse`
  - `PingStatsService.getFailures()` → `FailuresResponse`
  - `StatsBucket` record: `String bucket`, `Double avgResponseMs`, `long count`, `long failures`
  - `StatsResponse` record: `String granularity`, `List<StatsBucket> data`
  - `FailureItem` record: `Long id`, `LocalDateTime pingedAt`
  - `FailuresResponse` record: `List<FailureItem> failures`

- [ ] **Step 1: Create DTOs**

Create `src/main/java/com/example/ping/dto/StatsBucket.java`:
```java
package com.example.ping.dto;

public record StatsBucket(String bucket, Double avgResponseMs, long count, long failures) {}
```

Create `src/main/java/com/example/ping/dto/StatsResponse.java`:
```java
package com.example.ping.dto;

import java.util.List;

public record StatsResponse(String granularity, List<StatsBucket> data) {}
```

Create `src/main/java/com/example/ping/dto/FailureItem.java`:
```java
package com.example.ping.dto;

import java.time.LocalDateTime;

public record FailureItem(Long id, LocalDateTime pingedAt) {}
```

Create `src/main/java/com/example/ping/dto/FailuresResponse.java`:
```java
package com.example.ping.dto;

import java.util.List;

public record FailuresResponse(List<FailureItem> failures) {}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/example/ping/service/PingStatsServiceTest.java`:

```java
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
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./mvnw test -Dtest=PingStatsServiceTest
```
Expected: FAIL — `PingStatsService` does not exist

- [ ] **Step 4: Create `src/main/java/com/example/ping/service/PingStatsService.java`**

```java
package com.example.ping.service;

import com.example.ping.domain.PingResult;
import com.example.ping.dto.*;
import com.example.ping.repository.PingRepository;
import org.springframework.stereotype.Service;

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
        boolean byHour = "hour".equalsIgnoreCase(granularity);
        LocalDateTime since = byHour
                ? LocalDateTime.now().minusHours(48)
                : LocalDateTime.now().minusHours(2);

        List<PingResult> results = pingRepository.findSince(since);
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
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./mvnw test -Dtest=PingStatsServiceTest
```
Expected: 4 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add PingStatsService and response DTOs"
```

---

### Task 6: REST controllers

**Files:**
- Create: `src/main/java/com/example/ping/web/PingController.java`
- Create: `src/main/java/com/example/ping/web/UiController.java`
- Create: `src/test/java/com/example/ping/web/PingControllerTest.java`

**Interfaces:**
- Consumes: `PingStatsService.getStats(String)`, `PingStatsService.getFailures()`
- Produces:
  - `GET /api/pings/stats?granularity=minute|hour` → `StatsResponse` (JSON)
  - `GET /api/pings/failures?limit=50` → `FailuresResponse` (JSON)
  - `GET /` → Thymeleaf view `index`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/ping/web/PingControllerTest.java`:

```java
package com.example.ping.web;

import com.example.ping.dto.*;
import com.example.ping.service.PingStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

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
        when(pingStatsService.getStats("minute"))
                .thenReturn(new StatsResponse("minute", List.of(bucket)));

        mvc.perform(get("/api/pings/stats").param("granularity", "minute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("minute"))
                .andExpect(jsonPath("$.data[0].avgResponseMs").value(42.0))
                .andExpect(jsonPath("$.data[0].bucket").value("2026-08-04T10:05:00"));
    }

    @Test
    void statsEndpoint_defaultsToMinute() throws Exception {
        when(pingStatsService.getStats("minute"))
                .thenReturn(new StatsResponse("minute", List.of()));

        mvc.perform(get("/api/pings/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("minute"));
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=PingControllerTest
```
Expected: FAIL — `PingController` does not exist

- [ ] **Step 3: Create `src/main/java/com/example/ping/web/PingController.java`**

```java
package com.example.ping.web;

import com.example.ping.dto.FailuresResponse;
import com.example.ping.dto.StatsResponse;
import com.example.ping.service.PingStatsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pings")
public class PingController {

    private final PingStatsService pingStatsService;

    public PingController(PingStatsService pingStatsService) {
        this.pingStatsService = pingStatsService;
    }

    @GetMapping("/stats")
    public StatsResponse stats(@RequestParam(defaultValue = "minute") String granularity) {
        return pingStatsService.getStats(granularity);
    }

    @GetMapping("/failures")
    public FailuresResponse failures() {
        return pingStatsService.getFailures();
    }
}
```

- [ ] **Step 4: Create `src/main/java/com/example/ping/web/UiController.java`**

```java
package com.example.ping.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./mvnw test -Dtest=PingControllerTest
```
Expected: 3 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/
git commit -m "feat: add REST API endpoints and UI controller"
```

---

### Task 7: Download and bundle static assets

All assets must live under `src/main/resources/static/` — no CDN.

**Files:**
- Create: `src/main/resources/static/css/bootstrap.min.css`
- Create: `src/main/resources/static/js/bootstrap.bundle.min.js`
- Create: `src/main/resources/static/js/chart.umd.min.js`
- Create: `src/main/resources/static/js/hammer.min.js`
- Create: `src/main/resources/static/js/chartjs-plugin-zoom.min.js`

**Interfaces:**
- Produces: locally served JS/CSS files at `/css/bootstrap.min.css`, `/js/chart.umd.min.js`, etc.

- [ ] **Step 1: Create static asset directories**

```bash
mkdir -p src/main/resources/static/css
mkdir -p src/main/resources/static/js
```

- [ ] **Step 2: Download Bootstrap 5**

```bash
curl -L "https://github.com/twbs/bootstrap/releases/download/v5.3.3/bootstrap-5.3.3-dist.zip" \
  -o /tmp/bootstrap.zip
unzip -j /tmp/bootstrap.zip "bootstrap-5.3.3-dist/css/bootstrap.min.css" \
  -d src/main/resources/static/css/
unzip -j /tmp/bootstrap.zip "bootstrap-5.3.3-dist/js/bootstrap.bundle.min.js" \
  -d src/main/resources/static/js/
rm /tmp/bootstrap.zip
```

- [ ] **Step 3: Download Chart.js**

```bash
curl -L "https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js" \
  -o src/main/resources/static/js/chart.umd.min.js
```

- [ ] **Step 4: Download hammer.js**

```bash
curl -L "https://cdn.jsdelivr.net/npm/hammerjs@2.0.8/hammer.min.js" \
  -o src/main/resources/static/js/hammer.min.js
```

- [ ] **Step 5: Download chartjs-plugin-zoom**

```bash
curl -L "https://cdn.jsdelivr.net/npm/chartjs-plugin-zoom@2.0.1/dist/chartjs-plugin-zoom.min.js" \
  -o src/main/resources/static/js/chartjs-plugin-zoom.min.js
```

- [ ] **Step 6: Verify files exist**

```bash
ls -lh src/main/resources/static/css/bootstrap.min.css
ls -lh src/main/resources/static/js/
```
Expected: 5 files present, each > 10KB

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/
git commit -m "feat: bundle Bootstrap 5, Chart.js, hammer.js, chartjs-plugin-zoom locally"
```

---

### Task 8: Thymeleaf UI template + app.js

**Files:**
- Create: `src/main/resources/templates/index.html`
- Create: `src/main/resources/static/js/app.js`

**Interfaces:**
- Consumes: `GET /api/pings/stats?granularity=minute|hour`, `GET /api/pings/failures`
- Produces: single-page UI with live chart and failures table

- [ ] **Step 1: Create `src/main/resources/templates/index.html`**

```html
<!DOCTYPE html>
<html lang="en" data-bs-theme="dark">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Ping Monitor</title>
    <link rel="stylesheet" href="/css/bootstrap.min.css">
    <style>
        body { background-color: #0d1117; }
        .status-dot {
            display: inline-block;
            width: 10px; height: 10px;
            border-radius: 50%;
            margin-right: 6px;
        }
        .dot-ok  { background-color: #2ea043; }
        .dot-fail { background-color: #f85149; }
        #chart-container { position: relative; height: 340px; }
    </style>
</head>
<body>
<nav class="navbar navbar-dark bg-dark border-bottom border-secondary px-3">
    <span class="navbar-brand fw-bold">Ping Monitor</span>
    <span id="status-indicator" class="text-secondary small">Loading...</span>
</nav>

<div class="container-fluid py-4">

    <!-- Chart panel -->
    <div class="card bg-dark border-secondary mb-4">
        <div class="card-header d-flex justify-content-between align-items-center">
            <span class="fw-semibold">Response Time</span>
            <div class="d-flex gap-2">
                <div class="btn-group btn-group-sm" role="group">
                    <button id="btn-minute" type="button" class="btn btn-primary" onclick="setGranularity('minute')">By Minute</button>
                    <button id="btn-hour"   type="button" class="btn btn-outline-secondary" onclick="setGranularity('hour')">By Hour</button>
                </div>
                <button type="button" class="btn btn-sm btn-outline-secondary" onclick="resetZoom()">Reset Zoom</button>
            </div>
        </div>
        <div class="card-body">
            <div id="chart-container">
                <canvas id="pingChart"></canvas>
            </div>
        </div>
    </div>

    <!-- Failures panel -->
    <div class="card bg-dark border-secondary">
        <div class="card-header fw-semibold">Recent Failures</div>
        <div class="card-body p-0">
            <table class="table table-dark table-striped mb-0">
                <thead>
                    <tr><th>Timestamp</th></tr>
                </thead>
                <tbody id="failures-body">
                    <tr><td class="text-secondary">Loading...</td></tr>
                </tbody>
            </table>
        </div>
    </div>

</div>

<script src="/js/hammer.min.js"></script>
<script src="/js/chart.umd.min.js"></script>
<script src="/js/chartjs-plugin-zoom.min.js"></script>
<script src="/js/bootstrap.bundle.min.js"></script>
<script src="/js/app.js"></script>
</body>
</html>
```

- [ ] **Step 2: Create `src/main/resources/static/js/app.js`**

```javascript
Chart.register(ChartZoom);

let currentGranularity = 'minute';
let pingChart = null;

function initChart() {
    const ctx = document.getElementById('pingChart').getContext('2d');
    pingChart = new Chart(ctx, {
        type: 'line',
        data: { labels: [], datasets: [{
            label: 'Avg Response (ms)',
            data: [],
            borderColor: '#2ea043',
            backgroundColor: 'rgba(46,160,67,0.1)',
            borderWidth: 2,
            pointRadius: 3,
            tension: 0.3,
            fill: true
        }]},
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            scales: {
                x: { ticks: { color: '#8b949e', maxTicksLimit: 12 }, grid: { color: '#21262d' } },
                y: { ticks: { color: '#8b949e' }, grid: { color: '#21262d' }, title: { display: true, text: 'ms', color: '#8b949e' } }
            },
            plugins: {
                legend: { labels: { color: '#c9d1d9' } },
                zoom: {
                    pan: { enabled: true, mode: 'x' },
                    zoom: { wheel: { enabled: true }, pinch: { enabled: true }, mode: 'x' }
                }
            }
        }
    });
}

function resetZoom() {
    if (pingChart) pingChart.resetZoom();
}

function setGranularity(g) {
    currentGranularity = g;
    document.getElementById('btn-minute').className = g === 'minute' ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline-secondary';
    document.getElementById('btn-hour').className   = g === 'hour'   ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline-secondary';
    fetchAndUpdate();
}

function formatBucket(bucket) {
    return bucket.replace('T', ' ');
}

async function fetchAndUpdate() {
    try {
        const [statsRes, failRes] = await Promise.all([
            fetch('/api/pings/stats?granularity=' + currentGranularity),
            fetch('/api/pings/failures')
        ]);
        const stats = await statsRes.json();
        const failData = await failRes.json();

        // Update chart
        pingChart.data.labels = stats.data.map(b => formatBucket(b.bucket));
        pingChart.data.datasets[0].data = stats.data.map(b => b.avgResponseMs);
        pingChart.update('none');

        // Update status indicator
        const last = stats.data.length ? stats.data[stats.data.length - 1] : null;
        const indicator = document.getElementById('status-indicator');
        if (last) {
            const ok = last.failures < last.count;
            const dot = `<span class="status-dot ${ok ? 'dot-ok' : 'dot-fail'}"></span>`;
            const label = ok && last.avgResponseMs != null
                ? `Last ping: ${Math.round(last.avgResponseMs)}ms`
                : 'Last ping: FAILED';
            indicator.innerHTML = dot + label;
        }

        // Update failures table
        const tbody = document.getElementById('failures-body');
        if (!failData.failures.length) {
            tbody.innerHTML = '<tr><td class="text-secondary">No failures in the last 48 hours</td></tr>';
        } else {
            tbody.innerHTML = failData.failures
                .map(f => `<tr><td>${f.pingedAt.replace('T', ' ')}</td></tr>`)
                .join('');
        }
    } catch (e) {
        console.error('Fetch error:', e);
    }
}

initChart();
fetchAndUpdate();
setInterval(fetchAndUpdate, 5000);
```

- [ ] **Step 3: Compile and verify no template/JS syntax errors**

```bash
./mvnw compile
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/ src/main/resources/static/js/app.js
git commit -m "feat: add Thymeleaf UI with Chart.js chart and failures table"
```

---

### Task 9: Dockerfile + docker-compose.yml

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`

**Interfaces:**
- Produces: `docker compose up --build` starts the full app at `http://localhost:8080`

- [ ] **Step 1: Create `Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Wait — the Maven wrapper (`mvnw`) needs to be present. Add a step to copy it:

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src ./src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Generate the Maven wrapper (if not already present)**

```bash
mvn wrapper:wrapper -Dmaven=3.9.6
```
Or if Maven isn't installed locally, use the Spring Initializr-generated wrapper. The wrapper files needed are: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`.

- [ ] **Step 3: Create `docker-compose.yml`**

```yaml
services:
  db:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: ping
      MYSQL_DATABASE: pingdb
      MYSQL_USER: ping
      MYSQL_PASSWORD: ping
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "ping", "-pping"]
      interval: 5s
      timeout: 5s
      retries: 10

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://db:3306/pingdb
      SPRING_DATASOURCE_USERNAME: ping
      SPRING_DATASOURCE_PASSWORD: ping
    depends_on:
      db:
        condition: service_healthy

volumes:
  mysql_data:
```

- [ ] **Step 4: Build the Docker image**

```bash
docker compose build
```
Expected: image builds successfully

- [ ] **Step 5: Start the stack**

```bash
docker compose up -d
```

- [ ] **Step 6: Verify the app is running**

```bash
# Wait ~15s for MySQL to be ready and app to start
curl -s http://localhost:8080/api/pings/stats | head -c 200
```
Expected: JSON response with `granularity` and `data` fields

- [ ] **Step 7: Open the UI in a browser**

Navigate to `http://localhost:8080`. You should see:
- The navbar with "Ping Monitor" and a status indicator
- A line chart (may be empty for the first minute while data accumulates)
- A failures table

- [ ] **Step 8: Commit**

```bash
git add Dockerfile docker-compose.yml mvnw mvnw.cmd .mvn/
git commit -m "feat: add Dockerfile and docker-compose for local deployment"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] Spring Boot app — Task 1
- [x] HTTP ping every second — Task 3 (`fixedDelay = 1000`)
- [x] Persist success/failure + response time to MySQL — Tasks 2, 3
- [x] Delete data older than 48h — Task 4
- [x] REST API: `/api/pings/stats?granularity=minute|hour` — Tasks 5, 6
- [x] REST API: `/api/pings/failures` — Tasks 5, 6
- [x] Chart with average response time — Task 8
- [x] By-minute and by-hour toggle — Task 8
- [x] Zoom/pan on chart — Task 8 (chartjs-plugin-zoom)
- [x] Failures panel — Task 8
- [x] Status indicator (last ping) — Task 8
- [x] All assets bundled locally (no CDN) — Tasks 7, 8
- [x] Docker Compose deployment — Task 9
- [x] Bootstrap dark theme — Tasks 7, 8

**All tasks covered. No gaps.**

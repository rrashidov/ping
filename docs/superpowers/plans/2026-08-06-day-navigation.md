# Day Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ← / → day navigation buttons to the chart so users can view yesterday's ping data.

**Architecture:** Add an optional `?date=YYYY-MM-DD` param to the stats API backed by a bounded `findBetween` query; the frontend tracks `currentDate` and passes it with every fetch, and renders two nav buttons that disable at the retention boundary.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Chart.js, vanilla JS, Bootstrap 5

## Global Constraints

- Retention window is 48 hours — navigation limit is today and yesterday only
- Timezone stays as-is (`TZ=Europe/Sofia` server-side, local-time labels in JS)
- No new dependencies
- All existing tests must continue to pass

---

### Task 1: Add `findBetween` to repository and update service + service tests

**Files:**
- Modify: `src/main/java/com/example/ping/repository/PingRepository.java`
- Modify: `src/main/java/com/example/ping/service/PingStatsService.java`
- Modify: `src/test/java/com/example/ping/service/PingStatsServiceTest.java`

**Interfaces:**
- Produces: `PingRepository.findBetween(LocalDateTime since, LocalDateTime until): List<PingResult>`
- Produces: `PingStatsService.getStats(String granularity, LocalDate date): StatsResponse`

- [ ] **Step 1: Write failing tests for `getStats` with explicit date**

Replace the mock setup in `PingStatsServiceTest` — all existing tests called `repo.findSince(any())`; after this change they must call `repo.findBetween(any(), any())`. Update the three existing `getStats_*` tests and add one new test for a past date:

```java
// At top of class, ensure these imports are present:
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.Mockito.*;

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
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=PingStatsServiceTest -q 2>&1 | tail -20
```

Expected: compilation errors or failures because `findBetween` and the two-arg `getStats` don't exist yet.

- [ ] **Step 3: Add `findBetween` to `PingRepository`**

Add after the existing `findSince` method:

```java
@Query("""
    SELECT p FROM PingResult p
    WHERE p.pingedAt >= :since AND p.pingedAt < :until
    ORDER BY p.pingedAt ASC
    """)
List<PingResult> findBetween(@Param("since") LocalDateTime since,
                             @Param("until") LocalDateTime until);
```

- [ ] **Step 4: Update `PingStatsService.getStats` signature and query**

Change the method signature and body:

```java
public StatsResponse getStats(String granularity, LocalDate date) {
    boolean byHour = "hour".equalsIgnoreCase(granularity);
    LocalDateTime since = date.atStartOfDay();
    LocalDateTime until = date.plusDays(1).atStartOfDay();

    List<PingResult> results = pingRepository.findBetween(since, until);
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
```

- [ ] **Step 5: Run service tests to confirm they pass**

```bash
./mvnw test -pl . -Dtest=PingStatsServiceTest -q 2>&1 | tail -20
```

Expected: 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/ping/repository/PingRepository.java \
        src/main/java/com/example/ping/service/PingStatsService.java \
        src/test/java/com/example/ping/service/PingStatsServiceTest.java
git commit -m "feat: add findBetween query; getStats accepts explicit date"
```

---

### Task 2: Update controller to accept optional `?date` param

**Files:**
- Modify: `src/main/java/com/example/ping/web/PingController.java`
- Modify: `src/test/java/com/example/ping/web/PingControllerTest.java`

**Interfaces:**
- Consumes: `PingStatsService.getStats(String granularity, LocalDate date)`
- Produces: `GET /api/pings/stats?granularity=hour&date=YYYY-MM-DD` — returns `StatsResponse`; omitting `date` defaults to today; malformed `date` returns HTTP 400

- [ ] **Step 1: Write failing controller tests**

Update `PingControllerTest` — the existing `statsEndpoint_returnsJson` and `statsEndpoint_defaultsToMinute` tests call `pingStatsService.getStats("minute")` (one arg); after this change they must call `getStats("minute", LocalDate)`. Add two new tests:

```java
// Add imports at top:
// import java.time.LocalDate;

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
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=PingControllerTest -q 2>&1 | tail -20
```

Expected: compilation errors because `getStats` signature changed.

- [ ] **Step 3: Update `PingController.stats`**

```java
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

// Replace the stats method and add exception handler:

@GetMapping("/stats")
public StatsResponse stats(
        @RequestParam(defaultValue = "minute") String granularity,
        @RequestParam(required = false) String date) {
    LocalDate day = (date != null) ? LocalDate.parse(date) : LocalDate.now();
    return pingStatsService.getStats(granularity, day);
}

@ExceptionHandler(DateTimeParseException.class)
public ResponseEntity<String> handleBadDate(DateTimeParseException ex) {
    return ResponseEntity.badRequest().body("Invalid date format. Use YYYY-MM-DD.");
}
```

- [ ] **Step 4: Run controller tests to confirm they pass**

```bash
./mvnw test -pl . -Dtest=PingControllerTest -q 2>&1 | tail -20
```

Expected: 4 tests pass.

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test -q 2>&1 | tail -20
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/ping/web/PingController.java \
        src/test/java/com/example/ping/web/PingControllerTest.java
git commit -m "feat: stats endpoint accepts optional ?date param, defaults to today"
```

---

### Task 3: Frontend — day navigation state and UI

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/templates/index.html`

**Interfaces:**
- Consumes: `GET /api/pings/stats?granularity=<g>&date=YYYY-MM-DD`
- No new backend interfaces

- [ ] **Step 1: Add `currentDate` state and helpers to `app.js`**

Add at the top of `app.js`, after the existing `let pingChart = null;` line:

```js
let currentDate = new Date();
currentDate.setHours(0, 0, 0, 0);

function toDateParam(d) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

function isToday(d) {
    const t = new Date();
    return d.getFullYear() === t.getFullYear() &&
           d.getMonth() === t.getMonth() &&
           d.getDate() === t.getDate();
}

function isYesterday(d) {
    const y = new Date();
    y.setDate(y.getDate() - 1);
    return d.getFullYear() === y.getFullYear() &&
           d.getMonth() === y.getMonth() &&
           d.getDate() === y.getDate();
}

function updateNavUI() {
    const label = isToday(currentDate) ? 'Today' : 'Yesterday';
    document.getElementById('date-label').textContent = label;
    document.getElementById('btn-prev').disabled = isYesterday(currentDate);
    document.getElementById('btn-next').disabled = isToday(currentDate);
}

function navigateDay(delta) {
    currentDate.setDate(currentDate.getDate() + delta);
    updateNavUI();
    pingChart.options.plugins.annotation.annotations.nowLine =
        isToday(currentDate) ? buildNowAnnotation(currentGranularity) : { display: false };
    pingChart.resetZoom();
    fetchAndUpdate();
}
```

- [ ] **Step 2: Update `fetchAndUpdate` to include the date param**

Change the stats fetch line from:

```js
fetch('/api/pings/stats?granularity=' + currentGranularity),
```

to:

```js
fetch('/api/pings/stats?granularity=' + currentGranularity + '&date=' + toDateParam(currentDate)),
```

- [ ] **Step 3: Call `updateNavUI()` after `initChart()`**

At the bottom of `app.js`, after `initChart();`, add:

```js
updateNavUI();
```

- [ ] **Step 4: Add nav buttons to `index.html`**

In the chart card header, add the nav group before the existing granularity button group. Replace:

```html
            <div class="d-flex gap-2">
                <div class="btn-group btn-group-sm" role="group">
```

with:

```html
            <div class="d-flex gap-2 align-items-center">
                <div class="btn-group btn-group-sm" role="group">
                    <button id="btn-prev" type="button" class="btn btn-outline-secondary" onclick="navigateDay(-1)">&#8592;</button>
                    <span id="date-label" class="btn btn-sm btn-outline-secondary disabled" style="pointer-events:none;">Today</span>
                    <button id="btn-next" type="button" class="btn btn-outline-secondary" onclick="navigateDay(1)">&#8594;</button>
                </div>
                <div class="btn-group btn-group-sm" role="group">
```

Also close the extra wrapping `div` — the existing closing `</div>` for the outer `d-flex` stays; just ensure the new btn-group div is properly closed before the granularity group opens.

- [ ] **Step 5: Manual smoke test**

Start the app and verify:
1. Chart loads showing "Today" label, `←` button disabled, `→` button disabled.
2. Click `←` — label changes to "Yesterday", `←` becomes disabled, `→` becomes enabled, chart data updates to yesterday's date range, now-annotation disappears.
3. Click `→` — returns to "Today", now-annotation reappears, `→` disables.
4. Hour/minute toggle and Reset Zoom still work on both days.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/js/app.js \
        src/main/resources/templates/index.html
git commit -m "feat: add day navigation buttons to chart header"
```

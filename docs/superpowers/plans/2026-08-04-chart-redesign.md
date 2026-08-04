# Chart Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the rolling-window line chart with a full-day mixed bar+line chart showing today's avg response time (bars) and failure count (line), with a vertical "now" marker.

**Architecture:** Backend `PingStatsService` query window changes to today-from-midnight. Frontend `app.js` is rewritten to produce a mixed Chart.js chart with pre-generated full-day labels, two Y-axes, and a `chartjs-plugin-annotation` "now" line. `index.html` gets one new script tag and updated button defaults. A new local asset (`chartjs-plugin-annotation.min.js`) is downloaded and bundled.

**Tech Stack:** Java 21, Spring Boot 3.3.2, Chart.js 4.4.3, chartjs-plugin-annotation 3.1.0, chartjs-plugin-zoom 2.0.1, Bootstrap 5 dark theme.

## Global Constraints

- Java 21, Spring Boot 3.3.2, package `com.example.ping`
- All frontend assets must be bundled locally — no CDN URLs anywhere
- `chartjs-plugin-annotation` version: exactly 3.1.0
- Default granularity: `hour` (not `minute`)
- Chart always shows the full current calendar day (midnight to 23:59)
- Bar dataset: avg response ms, color `#2ea043`, left Y-axis (`y`)
- Line dataset: failure count, color `#f85149`, right Y-axis (`y1`), integer ticks, min 0
- "Now" annotation: vertical dashed line, color `#e3b341`, at current hour (hour view) or current minute (minute view)
- Future buckets have null data (gaps/empty bars)
- `StatsBucket`, `StatsResponse` record shapes unchanged — no REST API shape changes
- 5-second polling unchanged
- Status indicator and failures table unchanged

---

## File Structure

```
src/
├── main/
│   ├── java/com/example/ping/service/
│   │   └── PingStatsService.java              MODIFY — change query window to today-from-midnight
│   ├── resources/
│   │   ├── static/js/
│   │   │   ├── chartjs-plugin-annotation.min.js   CREATE — download from jsdelivr
│   │   │   └── app.js                         MODIFY — full rewrite for mixed chart
│   │   └── templates/
│   │       └── index.html                     MODIFY — add annotation script tag, fix button defaults
└── test/
    └── java/com/example/ping/service/
        └── PingStatsServiceTest.java          MODIFY — update tests for today-from-midnight window
```

---

### Task 1: Update `PingStatsService` query window + tests

**Files:**
- Modify: `src/main/java/com/example/ping/service/PingStatsService.java`
- Modify: `src/test/java/com/example/ping/service/PingStatsServiceTest.java`

**Interfaces:**
- Produces: `getStats(String granularity)` unchanged shape, queries from today at 00:00:00

- [ ] **Step 1: Update `PingStatsService.getStats` to query from today's midnight**

In `src/main/java/com/example/ping/service/PingStatsService.java`, replace the `since` calculation:

```java
// Remove these lines:
LocalDateTime since = byHour
        ? LocalDateTime.now().minusHours(48)
        : LocalDateTime.now().minusHours(2);

// Replace with:
LocalDateTime since = LocalDate.now().atStartOfDay();
```

Add import:
```java
import java.time.LocalDate;
```

- [ ] **Step 2: Update `PingStatsServiceTest`**

Replace full content of `src/test/java/com/example/ping/service/PingStatsServiceTest.java`:

```java
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
        when(repo.findSince(any())).thenReturn(List.of(new PingResult(t, true, 42L)));

        var service = new PingStatsService(repo);
        var response = service.getStats("minute");

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
        when(repo.findSince(any())).thenReturn(List.of(new PingResult(t, true, 100L)));

        var service = new PingStatsService(repo);
        var response = service.getStats("hour");

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
        when(repo.findSince(any())).thenReturn(List.of(
                new PingResult(t, true, 40L),
                new PingResult(t.plusSeconds(10), true, 60L),
                new PingResult(t.plusSeconds(20), false, null)
        ));

        var service = new PingStatsService(repo);
        var response = service.getStats("minute");

        var bucket = response.data().stream()
                .filter(b -> b.bucket().contains("T10:05:00"))
                .findFirst().orElseThrow();
        assertThat(bucket.count()).isEqualTo(3);
        assertThat(bucket.failures()).isEqualTo(1);
        assertThat(bucket.avgResponseMs()).isEqualTo(50.0);
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
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=PingStatsServiceTest
```
Expected: 4 tests pass

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/ping/service/PingStatsService.java \
        src/test/java/com/example/ping/service/PingStatsServiceTest.java
git commit -m "feat: change stats query window to today-from-midnight"
```

---

### Task 2: Download chartjs-plugin-annotation asset

**Files:**
- Create: `src/main/resources/static/js/chartjs-plugin-annotation.min.js`

**Interfaces:**
- Produces: locally served JS at `/js/chartjs-plugin-annotation.min.js`

- [ ] **Step 1: Download chartjs-plugin-annotation 3.1.0**

```bash
curl -L "https://cdn.jsdelivr.net/npm/chartjs-plugin-annotation@3.1.0/dist/chartjs-plugin-annotation.min.js" \
  -o src/main/resources/static/js/chartjs-plugin-annotation.min.js
```

- [ ] **Step 2: Verify**

```bash
ls -lh src/main/resources/static/js/chartjs-plugin-annotation.min.js
```
Expected: file exists, > 5KB

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/chartjs-plugin-annotation.min.js
git commit -m "feat: bundle chartjs-plugin-annotation 3.1.0 locally"
```

---

### Task 3: Rewrite `app.js` + update `index.html`

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/templates/index.html`

**Interfaces:**
- Consumes: `GET /api/pings/stats?granularity=hour|minute` → `{ granularity, data: [{bucket, avgResponseMs, count, failures}] }`
- Produces: mixed bar+line chart, full-day X-axis, "now" annotation

- [ ] **Step 1: Update `index.html`**

a) Add annotation plugin script tag after `chartjs-plugin-zoom.min.js` and before `app.js`:
```html
<script src="/js/chartjs-plugin-annotation.min.js"></script>
```

b) Replace the button group with "By Hour" first and active by default:
```html
<div class="btn-group btn-group-sm" role="group">
    <button id="btn-hour"   type="button" class="btn btn-primary" onclick="setGranularity('hour')">By Hour</button>
    <button id="btn-minute" type="button" class="btn btn-outline-secondary" onclick="setGranularity('minute')">By Minute</button>
</div>
```

- [ ] **Step 2: Replace entire content of `app.js`**

```javascript
Chart.register(ChartZoom);
Chart.register(ChartAnnotation);

let currentGranularity = 'hour';
let pingChart = null;

function generateDayLabels(granularity) {
    const labels = [];
    if (granularity === 'hour') {
        for (let h = 0; h < 24; h++) {
            labels.push(String(h).padStart(2, '0') + ':00');
        }
    } else {
        for (let h = 0; h < 24; h++) {
            for (let m = 0; m < 60; m++) {
                labels.push(String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0'));
            }
        }
    }
    return labels;
}

function nowLabel(granularity) {
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    return granularity === 'hour' ? hh + ':00' : hh + ':' + mm;
}

function buildNowAnnotation(granularity) {
    return {
        type: 'line',
        scaleID: 'x',
        value: nowLabel(granularity),
        borderColor: '#e3b341',
        borderWidth: 2,
        borderDash: [6, 3],
        label: {
            display: true,
            content: 'Now',
            color: '#e3b341',
            backgroundColor: 'transparent',
            font: { size: 11 },
            position: 'start'
        }
    };
}

function initChart() {
    const labels = generateDayLabels(currentGranularity);
    const ctx = document.getElementById('pingChart').getContext('2d');
    pingChart = new Chart(ctx, {
        data: {
            labels,
            datasets: [
                {
                    type: 'bar',
                    label: 'Avg Response (ms)',
                    data: new Array(labels.length).fill(null),
                    backgroundColor: 'rgba(46,160,67,0.7)',
                    borderColor: '#2ea043',
                    borderWidth: 1,
                    yAxisID: 'y'
                },
                {
                    type: 'line',
                    label: 'Failures',
                    data: new Array(labels.length).fill(null),
                    borderColor: '#f85149',
                    backgroundColor: 'transparent',
                    borderWidth: 2,
                    pointRadius: 2,
                    tension: 0.3,
                    yAxisID: 'y1'
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            scales: {
                x: {
                    ticks: { color: '#8b949e', maxTicksLimit: 24, autoSkip: true },
                    grid: { color: '#21262d' }
                },
                y: {
                    position: 'left',
                    min: 0,
                    ticks: { color: '#8b949e' },
                    grid: { color: '#21262d' },
                    title: { display: true, text: 'ms', color: '#8b949e' }
                },
                y1: {
                    position: 'right',
                    min: 0,
                    ticks: {
                        color: '#f85149',
                        stepSize: 1,
                        callback: v => Number.isInteger(v) ? v : null
                    },
                    grid: { drawOnChartArea: false },
                    title: { display: true, text: 'failures', color: '#f85149' }
                }
            },
            plugins: {
                legend: { labels: { color: '#c9d1d9' } },
                zoom: {
                    pan: { enabled: true, mode: 'x' },
                    zoom: { wheel: { enabled: true }, pinch: { enabled: true }, mode: 'x' }
                },
                annotation: {
                    annotations: { nowLine: buildNowAnnotation(currentGranularity) }
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
    document.getElementById('btn-hour').className   = g === 'hour'   ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline-secondary';
    document.getElementById('btn-minute').className = g === 'minute' ? 'btn btn-sm btn-primary' : 'btn btn-sm btn-outline-secondary';
    const labels = generateDayLabels(g);
    pingChart.data.labels = labels;
    pingChart.data.datasets[0].data = new Array(labels.length).fill(null);
    pingChart.data.datasets[1].data = new Array(labels.length).fill(null);
    pingChart.options.plugins.annotation.annotations.nowLine = buildNowAnnotation(g);
    pingChart.resetZoom();
    pingChart.update('none');
    fetchAndUpdate();
}

function bucketToLabel(bucket, granularity) {
    const timePart = bucket.split('T')[1];
    const parts = timePart.split(':');
    return granularity === 'hour' ? parts[0] + ':00' : parts[0] + ':' + parts[1];
}

async function fetchAndUpdate() {
    try {
        const [statsRes, failRes] = await Promise.all([
            fetch('/api/pings/stats?granularity=' + currentGranularity),
            fetch('/api/pings/failures')
        ]);
        const stats = await statsRes.json();
        const failData = await failRes.json();

        const labels = pingChart.data.labels;
        const avgData = new Array(labels.length).fill(null);
        const failCounts = new Array(labels.length).fill(null);

        for (const b of stats.data) {
            const label = bucketToLabel(b.bucket, currentGranularity);
            const idx = labels.indexOf(label);
            if (idx !== -1) {
                avgData[idx] = b.avgResponseMs;
                failCounts[idx] = b.failures > 0 ? b.failures : null;
            }
        }

        pingChart.data.datasets[0].data = avgData;
        pingChart.data.datasets[1].data = failCounts;
        pingChart.options.plugins.annotation.annotations.nowLine = buildNowAnnotation(currentGranularity);
        pingChart.update('none');

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

- [ ] **Step 3: Run compile**

```bash
./mvnw compile
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Run full test suite**

```bash
./mvnw test
```
Expected: 13 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/js/app.js \
        src/main/resources/templates/index.html
git commit -m "feat: redesign chart to full-day mixed bar+line with now annotation"
```

---

## Self-Review Checklist

- [x] Query window → today from midnight — Task 1
- [x] Bar dataset (avg response ms, green, left Y-axis) — Task 3
- [x] Line dataset (failure count, red, right Y-axis, integer ticks) — Task 3
- [x] Full-day X-axis labels pre-generated — Task 3
- [x] Future buckets null (gaps) — Task 3
- [x] "Now" vertical dashed line, yellow, updates on each poll — Task 3
- [x] Default granularity `hour` — Task 3
- [x] "By Hour" button default active — Task 3
- [x] `chartjs-plugin-annotation` bundled locally — Task 2
- [x] Registered as `ChartAnnotation` — Task 3
- [x] Status indicator unchanged — Task 3
- [x] Failures table unchanged — Task 3
- [x] 5-second polling unchanged — Task 3
- [x] No CDN URLs — Task 2 + 3
- [x] Tests updated for new query window — Task 1

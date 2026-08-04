# Ping Monitor — Design Spec
**Date:** 2026-08-04

## Overview

A personal internet connectivity monitoring tool. A Spring Boot application periodically pings google.com via HTTP, persists results in MySQL, and serves a single-page UI showing response time trends and failures. Runs locally via Docker Compose. Works fully offline (no CDN dependencies).

---

## Architecture

Two Docker Compose services:
- **app** — Spring Boot JAR (Java 21)
- **db** — MySQL 8

Spring Boot owns everything: scheduling, persistence, REST API, and the Thymeleaf UI. No separate frontend build step.

### Key components inside Spring Boot

| Component | Responsibility |
|---|---|
| `PingScheduler` | Runs every 1 second, fires an HTTP GET to `https://google.com`, records result |
| `PingRepository` | JPA repository for `ping_result` table |
| `CleanupScheduler` | Runs every 1 hour, deletes rows older than 48 hours |
| `PingStatsService` | Aggregates ping data for the REST API |
| `PingController` | REST endpoints (`/api/pings/stats`, `/api/pings/failures`) |
| `UiController` | Serves the Thymeleaf page at `GET /` |

---

## Data Model

Single table: `ping_result`

| Column | Type | Constraints |
|---|---|---|
| `id` | BIGINT | PK, auto-increment |
| `pinged_at` | DATETIME | NOT NULL |
| `success` | BOOLEAN | NOT NULL |
| `response_time_ms` | BIGINT | nullable (null when failed) |

Index on `pinged_at` for efficient range queries and cleanup.

Aggregation (averages per minute, per hour) is computed at query time — not stored.

---

## Ping Mechanism

- Protocol: HTTP GET to `https://google.com` using Java's `HttpClient` (JDK 11+) with a 5-second timeout.
- Success: any HTTP response received (2xx, 3xx, even 4xx/5xx counts as "reachable"). Only connection timeout / exception = failure.
- Response time: measured as wall-clock time from request start to first response byte.
- Runs on a single-threaded scheduled executor (`@Scheduled(fixedDelay = 1000)`). If a ping takes longer than 1 second, the next one waits — no overlap.

---

## Cleanup

- `CleanupScheduler` annotated with `@Scheduled(cron = "0 0 * * * *")` (top of every hour).
- Deletes all rows where `pinged_at < NOW() - INTERVAL 48 HOUR` via a single JPQL bulk delete.

---

## REST API

### `GET /api/pings/stats?granularity=minute|hour`

Returns aggregated ping data for the chart.

**Query params:**
- `granularity`: `minute` (default) or `hour`

**Response:**
```json
{
  "granularity": "minute",
  "data": [
    { "bucket": "2026-08-04T10:00:00", "avgResponseMs": 42.3, "count": 58, "failures": 2 },
    ...
  ]
}
```

- `minute` granularity: last 2 hours of data, bucketed by minute
- `hour` granularity: last 48 hours of data, bucketed by hour
- Buckets with all failures (no successful pings) have `avgResponseMs: null`

### `GET /api/pings/failures?limit=50`

Returns the most recent failed pings.

**Response:**
```json
{
  "failures": [
    { "id": 1234, "pingedAt": "2026-08-04T10:05:32" },
    ...
  ]
}
```

Default and maximum `limit`: 50.

### `GET /`

Serves the Thymeleaf HTML page.

---

## UI

Single Thymeleaf page (`index.html`). All static assets bundled under `src/main/resources/static` — no CDN calls.

### Bundled assets
- `bootstrap.min.css` + `bootstrap.bundle.min.js` (Bootstrap 5, dark theme)
- `chart.umd.min.js` (Chart.js)
- `hammer.min.js` (required by zoom plugin)
- `chartjs-plugin-zoom.min.js`

### Layout
1. **Header bar** — app title + status indicator: colored dot (green/red) + "Last ping: 42ms" or "Last ping: FAILED", updated every 5 seconds.
2. **Chart panel** — Chart.js line chart of average response time. Toggle buttons: "By Minute" / "By Hour". Zoom/pan enabled (scroll to zoom, drag to pan). Reset zoom button.
3. **Failures panel** — table of recent failures: timestamp column. Shows "No failures in the last 48 hours" when empty.

### Data refresh
- `setInterval` at 5 seconds calls `fetch('/api/pings/stats?granularity=<current>')` and `fetch('/api/pings/failures')`.
- Chart and failures table updated in place (no page reload).
- Last ping status derived from the most recent data point in the stats response.

---

## Docker Compose

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

Spring Boot uses Flyway for DB migrations (schema created automatically on first start).

---

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Persistence | Spring Data JPA + Hibernate |
| Database | MySQL 8 |
| Migrations | Flyway |
| Templating | Thymeleaf |
| Charting | Chart.js + chartjs-plugin-zoom + hammerjs |
| Styling | Bootstrap 5 (dark theme) |
| Build | Maven |
| Runtime | Docker Compose |

---

## Out of Scope

- Authentication/authorization (personal local tool)
- Alerts/notifications
- Pinging targets other than google.com
- Data export

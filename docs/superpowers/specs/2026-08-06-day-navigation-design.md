# Day Navigation Design

**Date:** 2026-08-06  
**Feature:** Scroll back to yesterday's data via ← / → navigation buttons

## Overview

The chart currently shows only today's data (from midnight to now). Since the DB retains 48 hours of pings, yesterday's full day is available but unreachable. This feature adds ← / → day navigation buttons to the chart header so users can view any day within the retention window (today or yesterday).

## Backend

### Repository

Add `findBetween(since, until)` to `PingRepository`:

```java
@Query("SELECT p FROM PingResult p WHERE p.pingedAt >= :since AND p.pingedAt < :until ORDER BY p.pingedAt ASC")
List<PingResult> findBetween(@Param("since") LocalDateTime since, @Param("until") LocalDateTime until);
```

The existing `findSince` is no longer used by `getStats` but stays for now (used by nothing else; can be removed later).

### Service

`PingStatsService.getStats` gains a `LocalDate date` parameter (defaults to `LocalDate.now()` when absent). The query window becomes:

- `since = date.atStartOfDay()`
- `until = date.plusDays(1).atStartOfDay()`

### Controller

`PingController.getStats` accepts an optional `?date=YYYY-MM-DD` query param. When absent, `LocalDate.now()` is used — fully backwards-compatible. The date is parsed with `LocalDate.parse(dateStr)` and passed to the service.

## Frontend

### State

`app.js` gains a `currentDate` variable — a JS `Date` object initialized to today (local midnight). A helper `toDateParam(d)` formats it as `YYYY-MM-DD` for the API call. A helper `isToday(d)` returns true when `d` equals today's date.

### API call

`fetchAndUpdate` passes both params: `/api/pings/stats?granularity=hour&date=2026-08-06`.

### Chart behaviour

- When viewing a past day: the now-annotation is hidden (`display: false` on the annotation).
- When viewing today: now-annotation is shown as before.
- Granularity toggle (hour / minute) and reset-zoom continue to work on whichever day is selected.

### UI — chart header

New controls added to the existing button row in the card header:

```
← [Yesterday]  →     |  By Hour  By Minute  |  Reset Zoom
```

- `←` button: go one day back (disabled if already at yesterday — i.e. `currentDate` is yesterday, since data older than 48h is pruned).
- `→` button: go one day forward (disabled when `currentDate` is today).
- Date label: shows "Today" when on today, "Yesterday" when on yesterday.
- Clicking either arrow calls `navigateDay(delta)` which updates `currentDate`, refreshes the label and button states, reinitialises the now-annotation, and calls `fetchAndUpdate`.

### Failures table

Unchanged — already shows the last 48h regardless of selected day.

## Constraints

- Retention is 48 hours (pruned hourly). Navigation is limited to today and yesterday only — the `←` button is disabled at yesterday.
- No timezone changes — the existing `TZ=Europe/Sofia` server-side setup and local-time label logic are preserved.
- The `?date` param is validated; a malformed value returns HTTP 400.

## Out of scope

- A full date picker (easy to add later given the parameterised API)
- Changing retention window
- Showing data gaps for hours before the retention cutoff

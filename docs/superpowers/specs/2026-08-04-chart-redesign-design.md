# Chart Redesign — Design Spec
**Date:** 2026-08-04

## Overview

Redesign the Ping Monitor chart from a rolling-window line chart to a full-day mixed bar+line chart showing the current calendar day. The chart displays average response time as bars and failure count as a line, with a vertical "now" marker.

---

## What Changes

### Backend — `PingStatsService`

- `getStats(granularity)` query window changes from rolling (last 2h / last 48h) to **today from midnight to now** for both granularities.
- `minute` granularity: `since = today at 00:00:00`
- `hour` granularity: `since = today at 00:00:00`
- Response shape (`StatsBucket`, `StatsResponse`) is unchanged — `avgResponseMs`, `count`, `failures` per bucket are exactly what the new chart needs.

### Frontend — `app.js`

- Chart type: **mixed** — bar dataset (avg response ms, left Y-axis) + line dataset (failure count, right Y-axis)
- X-axis: always covers the full current day. For `hour` granularity: all 24 hour labels (00:00–23:00). For `minute` granularity: all 1440 minute labels (00:00–23:59). Future buckets have null data (rendered as gaps/empty bars).
- **"Now" annotation**: vertical line at the current hour (hour view) or current minute (minute view), drawn via `chartjs-plugin-annotation`, bundled locally.
- Zoom/pan kept (chartjs-plugin-zoom).
- Default granularity changes from `minute` to `hour` (more useful for a full-day view).
- Toggle buttons: "By Hour" (default, active) / "By Minute".
- Reset Zoom button kept.
- Status indicator and failures table below the chart are unchanged.
- 5-second polling unchanged.

### New bundled asset

- `chartjs-plugin-annotation` v3.x — downloaded locally to `src/main/resources/static/js/chartjs-plugin-annotation.min.js`
- Added to `index.html` script load order: after `chartjs-plugin-zoom.min.js`, before `app.js`
- Registered in `app.js`: `Chart.register(ChartAnnotation)`

---

## Chart Visual Spec

- **Bar dataset**: `avgResponseMs` per bucket. Color: `#2ea043` (green). Null for future/empty buckets.
- **Line dataset**: `failures` count per bucket. Color: `#f85149` (red). `pointRadius: 2`. Right Y-axis. Null for future/empty buckets.
- **Left Y-axis** (`y`): ms, label "ms", min 0.
- **Right Y-axis** (`y1`): failure count, label "failures", min 0, integer ticks.
- **"Now" annotation**: vertical dashed line, color `#e3b341` (yellow), at the label matching current hour (hour view) or current minute (minute view).
- **X-axis**: all buckets for the day pre-generated as labels; data arrays are sparse (null for buckets not yet reached or with no data).

---

## Out of Scope

- Changing the failures table below the chart
- Changing the status indicator
- Changing the REST API shape
- Persisting granularity preference across page reloads

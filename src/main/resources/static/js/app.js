Chart.register(ChartZoom);
Chart.register(window["chartjs-plugin-annotation"]);

let currentGranularity = 'hour';
let pingChart = null;

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
        label: { display: false }
    };
}

function initChart() {
    Chart.getChart("pingChart")?.destroy();
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
                    zoom: { drag: { enabled: true }, pinch: { enabled: true }, mode: 'x' }
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
    pingChart.options.plugins.annotation.annotations.nowLine =
        isToday(currentDate) ? buildNowAnnotation(g) : { display: false };
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
            fetch('/api/pings/stats?granularity=' + currentGranularity + '&date=' + toDateParam(currentDate)),
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
        pingChart.options.plugins.annotation.annotations.nowLine =
            isToday(currentDate) ? buildNowAnnotation(currentGranularity) : { display: false };
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
updateNavUI();
fetchAndUpdate();
setInterval(fetchAndUpdate, 5000);

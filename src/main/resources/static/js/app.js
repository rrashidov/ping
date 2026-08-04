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

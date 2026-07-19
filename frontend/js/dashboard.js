// API Base URL (Relative to hosted path)
const API_URL = '';

let authToken = localStorage.getItem('token');
let shortCode = '';
let charts = {};

document.addEventListener('DOMContentLoaded', () => {
    // 1. Verify Authentication
    if (!authToken) {
        window.location.href = '/';
        return;
    }

    // 2. Parse Query Code
    const urlParams = new URLSearchParams(window.location.search);
    shortCode = urlParams.get('code');

    if (!shortCode) {
        showToast('No short code provided.', 'error');
        setTimeout(() => { window.location.href = '/'; }, 2000);
        return;
    }

    // Initialize display fields
    document.getElementById('display-short-code').innerText = shortCode;
    const shortUrl = window.location.origin + '/' + shortCode;
    document.getElementById('short-url-copy-field').value = shortUrl;

    // 3. Fetch Analytics Data
    fetchAnalytics();
});

async function fetchAnalytics() {
    try {
        const response = await fetch(`${API_URL}/api/analytics/${shortCode}`, {
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        if (!response.ok) {
            if (response.status === 401 || response.status === 403) {
                showToast('Session expired or unauthorized.', 'error');
                setTimeout(() => { logout(); }, 2000);
                return;
            }
            throw new Error('Failed to load analytics.');
        }

        const data = await response.json();
        
        // 4. Update Summary Cards
        document.getElementById('stat-total-clicks').innerText = data.totalClicks;
        
        // We also need the original url and expiry from a standard URL info list,
        // or we can lookup our url metadata from the main links API
        // For efficiency, we will fetch Url details from `/api/urls/my-urls` to populate metadata
        fetchUrlDetails(data);

    } catch (err) {
        showToast(err.message, 'error');
    }
}

async function fetchUrlDetails(analyticsData) {
    try {
        const response = await fetch(`${API_URL}/api/urls/my-urls`, {
            headers: { 'Authorization': `Bearer ${authToken}` }
        });

        if (response.ok) {
            const urls = await response.json();
            const matchedUrl = urls.find(u => u.shortCode === shortCode);
            if (matchedUrl) {
                const origLink = document.getElementById('display-original-url');
                origLink.innerText = matchedUrl.originalUrl;
                origLink.href = matchedUrl.originalUrl;

                document.getElementById('stat-created').innerText = new Date(matchedUrl.createdAt).toLocaleDateString();
                
                if (matchedUrl.expiresAt) {
                    const expiryDate = new Date(matchedUrl.expiresAt);
                    document.getElementById('stat-expires').innerText = expiryDate.toLocaleDateString();
                    if (expiryDate < new Date()) {
                        const statusBadge = document.getElementById('stat-status');
                        statusBadge.innerText = 'Expired';
                        statusBadge.style.color = 'var(--danger)';
                    } else {
                        const statusBadge = document.getElementById('stat-status');
                        statusBadge.innerText = 'Active';
                        statusBadge.style.color = 'var(--success)';
                    }
                } else {
                    document.getElementById('stat-expires').innerText = 'Never';
                    const statusBadge = document.getElementById('stat-status');
                    statusBadge.innerText = 'Active';
                    statusBadge.style.color = 'var(--success)';
                }
            }
        }
        
        // Load the charts with the loaded analytics data
        renderCharts(analyticsData);
    } catch (e) {
        console.error('Failed to load URL metadata details', e);
        renderCharts(analyticsData);
    }
}

// Chart.js Theme Configurations
Chart.defaults.color = '#9ca3af';
Chart.defaults.font.family = "'Inter', sans-serif";

const chartColors = {
    blue: '#00f2fe',
    purple: '#4facfe',
    coral: '#fb923c',
    teal: '#2dd4bf',
    green: '#34d399',
    pink: '#f87171',
    yellow: '#fbbf24',
    indigo: '#818cf8',
    grid: 'rgba(255, 255, 255, 0.05)'
};

function renderCharts(data) {
    destroyExistingCharts();

    // 1. Click traffic over time (Line)
    const timeLabels = Object.keys(data.clicksOverTime).sort();
    const timeValues = timeLabels.map(k => data.clicksOverTime[k]);

    const ctxTime = document.getElementById('chart-clicks-time').getContext('2d');
    
    // Create soft gradient for line chart background
    const lineGradient = ctxTime.createLinearGradient(0, 0, 0, 300);
    lineGradient.addColorStop(0, 'rgba(0, 242, 254, 0.25)');
    lineGradient.addColorStop(1, 'rgba(0, 242, 254, 0)');

    charts.clicksTime = new Chart(ctxTime, {
        type: 'line',
        data: {
            labels: timeLabels.length ? timeLabels : ['No Data'],
            datasets: [{
                label: 'Clicks',
                data: timeValues.length ? timeValues : [0],
                borderColor: chartColors.blue,
                backgroundColor: lineGradient,
                fill: true,
                tension: 0.35,
                borderWidth: 3,
                pointBackgroundColor: chartColors.purple,
                pointBorderColor: '#ffffff',
                pointRadius: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                x: { grid: { color: chartColors.grid } },
                y: { grid: { color: chartColors.grid }, beginAtZero: true, ticks: { precision: 0 } }
            }
        }
    });

    // 2. Locations Distribution (Bar)
    const locLabels = Object.keys(data.locations);
    const locValues = locLabels.map(k => data.locations[k]);
    const ctxLoc = document.getElementById('chart-locations').getContext('2d');
    charts.locations = new Chart(ctxLoc, {
        type: 'bar',
        data: {
            labels: locLabels.length ? locLabels : ['No Data'],
            datasets: [{
                data: locValues.length ? locValues : [0],
                backgroundColor: chartColors.purple,
                borderRadius: 6
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                x: { grid: { display: false } },
                y: { grid: { color: chartColors.grid }, beginAtZero: true, ticks: { precision: 0 } }
            }
        }
    });

    // 3. Referrers Distribution (Horizontal Bar)
    const refLabels = Object.keys(data.referrers);
    const refValues = refLabels.map(k => data.referrers[k]);
    const ctxRef = document.getElementById('chart-referrers').getContext('2d');
    charts.referrers = new Chart(ctxRef, {
        type: 'bar',
        data: {
            labels: refLabels.length ? refLabels : ['No Data'],
            datasets: [{
                data: refValues.length ? refValues : [0],
                backgroundColor: chartColors.teal,
                borderRadius: 6
            }]
        },
        options: {
            indexAxis: 'y',
            responsive: true,
            maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                x: { grid: { color: chartColors.grid }, beginAtZero: true, ticks: { precision: 0 } },
                y: { grid: { display: false } }
            }
        }
    });

    // 4. Devices Distribution (Doughnut)
    const devLabels = Object.keys(data.devices);
    const devValues = devLabels.map(k => data.devices[k]);
    const ctxDev = document.getElementById('chart-devices').getContext('2d');
    charts.devices = new Chart(ctxDev, {
        type: 'doughnut',
        data: {
            labels: devLabels.length ? devLabels : ['No Data'],
            datasets: [{
                data: devValues.length ? devValues : [1],
                backgroundColor: [chartColors.blue, chartColors.purple, chartColors.pink, chartColors.yellow],
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { position: 'bottom', labels: { padding: 15 } }
            },
            cutout: '70%'
        }
    });

    // 5. Browsers Distribution (Doughnut)
    const browLabels = Object.keys(data.browsers);
    const browValues = browLabels.map(k => data.browsers[k]);
    const ctxBrow = document.getElementById('chart-browsers').getContext('2d');
    charts.browsers = new Chart(ctxBrow, {
        type: 'doughnut',
        data: {
            labels: browLabels.length ? browLabels : ['No Data'],
            datasets: [{
                data: browValues.length ? browValues : [1],
                backgroundColor: [chartColors.teal, chartColors.coral, chartColors.indigo, chartColors.green],
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { position: 'bottom', labels: { padding: 15 } }
            },
            cutout: '70%'
        }
    });
}

function destroyExistingCharts() {
    Object.keys(charts).forEach(key => {
        if (charts[key]) {
            charts[key].destroy();
        }
    });
    charts = {};
}

function copyShortUrl() {
    const copyField = document.getElementById('short-url-copy-field');
    copyField.select();
    copyField.setSelectionRange(0, 99999);
    navigator.clipboard.writeText(copyField.value).then(() => {
        showToast('Copied to clipboard!', 'success');
    }).catch(() => {
        showToast('Failed to copy link.', 'error');
    });
}

function logout() {
    localStorage.clear();
    window.location.href = '/';
}

function showToast(message, type = 'success') {
    const toast = document.getElementById('toast');
    const msgSpan = document.getElementById('toast-message');
    const iconSpan = document.getElementById('toast-icon');

    toast.className = `toast toast-${type} show`;
    msgSpan.innerText = message;
    iconSpan.innerText = type === 'success' ? '✅' : '❌';

    setTimeout(() => {
        toast.classList.remove('show');
    }, 3000);
}

const chartContainer = document.getElementById('chart');
const hoursInput = document.getElementById('hoursInput');
const applyBtn = document.getElementById('applyBtn');
const statusEl = document.getElementById('status');

const chart = LightweightCharts.createChart(chartContainer, {
    layout: {
        background: { color: '#020617' },
        textColor: '#cbd5e1',
    },
    grid: {
        vertLines: { color: '#0f172a' },
        horzLines: { color: '#0f172a' },
    },
    width: chartContainer.clientWidth,
    height: chartContainer.clientHeight,
    rightPriceScale: { borderColor: '#1e293b' },
    timeScale: { borderColor: '#1e293b' },
});

const candleSeries = chart.addCandlestickSeries({
    upColor: '#22c55e',
    downColor: '#ef4444',
    borderUpColor: '#22c55e',
    borderDownColor: '#ef4444',
    wickUpColor: '#22c55e',
    wickDownColor: '#ef4444',
});

window.addEventListener('resize', () => {
    chart.applyOptions({
        width: chartContainer.clientWidth,
        height: chartContainer.clientHeight,
    });
});

let lastOpenTime = 0;
let timerId = null;

function setStatus(message) {
    statusEl.textContent = message;
}

function toChartCandle(c) {
    return {
        time: Math.floor(c.openTime / 1000),
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
    };
}

async function fetchHistory(hours) {
    const response = await fetch(`/api/candles?hours=${hours}`);
    if (!response.ok) {
        throw new Error(`Błąd pobierania historii: ${response.status}`);
    }

    const data = await response.json();
    const candles = data.map(toChartCandle);
    candleSeries.setData(candles);

    if (data.length > 0) {
        lastOpenTime = data[data.length - 1].openTime;
    }

    chart.timeScale().fitContent();
}

async function fetchLatest() {
    if (!lastOpenTime) {
        return;
    }

    const fromOpenTime = Math.max(0, lastOpenTime - 60_000);
    const response = await fetch(`/api/latest?fromOpenTime=${fromOpenTime}`);
    if (!response.ok) {
        throw new Error(`Błąd pobierania live: ${response.status}`);
    }

    const data = await response.json();
    if (!Array.isArray(data) || data.length === 0) {
        return;
    }

    data.forEach((c) => {
        candleSeries.update(toChartCandle(c));
        if (c.openTime > lastOpenTime) {
            lastOpenTime = c.openTime;
        }
    });
}

async function initialize() {
    const hours = Math.min(240, Math.max(1, Number(hoursInput.value) || 6));
    hoursInput.value = String(hours);

    setStatus(`Pobieranie ostatnich ${hours}h...`);
    await fetchHistory(hours);
    setStatus(`Załadowano. Ostatnia świeca: ${new Date(lastOpenTime).toLocaleTimeString()}`);

    if (timerId) {
        clearInterval(timerId);
    }

    timerId = setInterval(async () => {
        try {
            await fetchLatest();
            setStatus(`Live: ${new Date().toLocaleTimeString()}`);
        } catch (error) {
            setStatus(error.message);
        }
    }, 10_000);
}

applyBtn.addEventListener('click', () => {
    initialize().catch((error) => setStatus(error.message));
});

initialize().catch((error) => setStatus(error.message));

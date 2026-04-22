/* ===== Dawn AI Frontend ===== */

const API = {
    chat: '/api/v1/chat',
    chatStream: '/api/v1/chat/stream',
    chatSimple: '/api/v1/chat/simple',
    ragIngest: '/api/v1/rag/ingest',
    ragSearch: '/api/v1/rag/search',
    health: '/actuator/health',
    metrics: '/actuator/metrics',
};

// ===== State =====
const state = {
    sessionId: null,
    isLoading: false,
    streamMode: true,  // default to SSE streaming
};

// ===== DOM References =====
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

// ===== Init =====
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initTheme();
    initChat();
    initKnowledge();
    initDashboard();
    newSession();
});

// ===== Navigation =====
function initNavigation() {
    $$('.nav-item').forEach(btn => {
        btn.addEventListener('click', () => {
            const page = btn.dataset.page;
            $$('.nav-item').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            $$('.page').forEach(p => p.classList.remove('active'));
            $(`#page-${page}`).classList.add('active');

            if (page === 'dashboard') refreshDashboard();
        });
    });
}

// ===== Theme =====
function initTheme() {
    const saved = localStorage.getItem('dawn-theme');
    if (saved === 'dark') document.documentElement.setAttribute('data-theme', 'dark');

    $('#themeToggle').addEventListener('click', () => {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        if (isDark) {
            document.documentElement.removeAttribute('data-theme');
            localStorage.setItem('dawn-theme', 'light');
        } else {
            document.documentElement.setAttribute('data-theme', 'dark');
            localStorage.setItem('dawn-theme', 'dark');
        }
    });
}

// ===== Chat =====
function initChat() {
    const input = $('#chatInput');
    const sendBtn = $('#sendBtn');

    // Auto-resize textarea
    input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
    });

    // Send on Enter (Shift+Enter for newline)
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    sendBtn.addEventListener('click', sendMessage);
    $('#newSessionBtn').addEventListener('click', () => {
        newSession();
        toast('New session created', 'info');
    });
}

function newSession() {
    state.sessionId = 'session-' + Date.now().toString(36);
    $('#sessionId').textContent = state.sessionId;

    const messages = $('#chatMessages');
    messages.innerHTML = `
        <div class="welcome-message">
            <h3>Welcome to Dawn AI</h3>
            <p>Start a conversation to test the AI agent.</p>
        </div>
    `;
}

async function sendMessage() {
    const input = $('#chatInput');
    const message = input.value.trim();
    if (!message || state.isLoading) return;

    state.isLoading = true;
    $('#sendBtn').disabled = true;

    // Remove welcome message
    const welcome = $('#chatMessages .welcome-message');
    if (welcome) welcome.remove();

    // Add user message
    appendMessage('user', message);
    input.value = '';
    input.style.height = 'auto';

    if (state.streamMode) {
        await sendMessageStream(message);
    } else {
        await sendMessageSync(message);
    }

    state.isLoading = false;
    $('#sendBtn').disabled = false;
    $('#chatInput').focus();
}

async function sendMessageStream(message) {
    const typingEl = showTyping();
    const assistantDiv = createAssistantPlaceholder();

    try {
        const res = await fetch(API.chatStream, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, sessionId: state.sessionId }),
        });

        typingEl.remove();

        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            assistantDiv.querySelector('.message-bubble').textContent = `Error: ${err.message || res.statusText}`;
            toast('Request failed: ' + (err.message || res.statusText), 'error');
            return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let eventName = '';
        let streamMeta = null;

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop(); // keep incomplete last line

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    eventName = line.slice(6).trim();
                } else if (line.startsWith('data:')) {
                    const raw = line.slice(5).trim();
                    if (!raw) continue;
                    try {
                        const envelope = JSON.parse(raw);
                        handleStreamEvent(eventName || envelope.event, envelope, assistantDiv);
                        if (envelope.event === 'done') streamMeta = envelope.data;
                        if (envelope.event === 'connected' && envelope.data && envelope.data.sessionId) {
                            state.sessionId = envelope.data.sessionId;
                            $('#sessionId').textContent = envelope.data.sessionId;
                        }
                    } catch (e) {
                        // skip malformed lines
                    }
                    eventName = '';
                } else if (line === '') {
                    eventName = '';
                }
            }
        }

        if (streamMeta) {
            finaliseAssistantMessage(assistantDiv, streamMeta);
        }

    } catch (err) {
        typingEl.remove();
        assistantDiv.querySelector('.message-bubble').textContent = `Network error: ${err.message}`;
        toast('Network error', 'error');
    }
}

function handleStreamEvent(type, envelope, assistantDiv) {
    const data = envelope.data || {};
    switch (type) {
        case 'plan_thinking': {
            const planThinkingPanel = getOrCreateThinkingPanel(
                assistantDiv,
                'plan-thinking-panel',
                '规划中...'
            );
            planThinkingPanel.querySelector('.thinking-content').textContent += data.content || '';
            break;
        }
        case 'thinking': {
            const thinkingPanel = getOrCreateThinkingPanel(
                assistantDiv,
                'answer-thinking-panel',
                '思考中...'
            );
            thinkingPanel.querySelector('.thinking-content').textContent += data.content || '';
            break;
        }
        case 'token': {
            const thinkingPanel = assistantDiv.querySelector('.answer-thinking-panel');
            if (thinkingPanel) {
                thinkingPanel.querySelector('.thinking-label').textContent = '已思考';
                thinkingPanel.classList.add('done');
            }
            const bubble = assistantDiv.querySelector('.message-bubble');
            bubble.textContent += data.content || '';
            $('#chatMessages').scrollTop = $('#chatMessages').scrollHeight;
            break;
        }
        case 'step': {
            let tracePanel = assistantDiv.querySelector('.stream-trace');
            if (!tracePanel) {
                tracePanel = document.createElement('div');
                tracePanel.className = 'stream-trace';
                assistantDiv.appendChild(tracePanel);
            }
            const stepEl = document.createElement('div');
            stepEl.className = 'step-item';
            stepEl.innerHTML = `
                <div class="step-header">
                    <span class="step-number">${data.stepNumber}</span>
                    <span class="step-tool">${escapeHtml(data.toolName || '')}</span>
                    <span class="step-duration">${data.durationMs || 0}ms</span>
                </div>
            `;
            tracePanel.appendChild(stepEl);
            break;
        }
        case 'plan': {
            const planThinkingPanel = assistantDiv.querySelector('.plan-thinking-panel');
            if (planThinkingPanel) {
                planThinkingPanel.querySelector('.thinking-label').textContent = '已完成规划';
                planThinkingPanel.classList.add('done');
            }
            let planEl = assistantDiv.querySelector('.stream-plan');
            if (!planEl) {
                planEl = document.createElement('div');
                planEl.className = 'stream-plan';
                assistantDiv.insertBefore(planEl, assistantDiv.querySelector('.message-bubble'));
            }
            planEl.textContent = data.summary || '';
            break;
        }
        case 'error': {
            assistantDiv.querySelector('.message-bubble').textContent =
                `[${data.code}] ${data.message}`;
            break;
        }
    }
}

function getOrCreateThinkingPanel(assistantDiv, panelClassName, label) {
    let thinkingPanel = assistantDiv.querySelector(`.${panelClassName}`);
    if (!thinkingPanel) {
        thinkingPanel = document.createElement('div');
        thinkingPanel.className = `thinking-panel ${panelClassName}`;
        thinkingPanel.innerHTML = `
            <button class="thinking-toggle" onclick="toggleThinking(this)">
                <span class="thinking-icon">💭</span>
                <span class="thinking-label">${escapeHtml(label)}</span>
                <svg class="thinking-chevron" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
            </button>
            <div class="thinking-content"></div>
        `;
        assistantDiv.insertBefore(thinkingPanel, assistantDiv.querySelector('.message-bubble'));
    }
    return thinkingPanel;
}

function createAssistantPlaceholder() {
    const container = $('#chatMessages');
    const div = document.createElement('div');
    div.className = 'message assistant';
    div.innerHTML = '<div class="message-bubble"></div>';
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return div;
}

function finaliseAssistantMessage(div, meta) {
    const parts = [];
    if (meta.model) parts.push(`Model: ${meta.model}`);
    if (meta.durationMs) parts.push(`${meta.durationMs}ms`);
    if (meta.totalSteps > 0) parts.push(`${meta.totalSteps} tool calls`);
    if (meta.planSummary) parts.push(meta.planSummary);

    if (parts.length > 0) {
        const metaEl = document.createElement('div');
        metaEl.className = 'message-meta';
        metaEl.innerHTML = parts.map(p => `<span>${escapeHtml(p)}</span>`).join('');
        div.appendChild(metaEl);
    }

    // Replace stream-trace items with full collapsible steps panel if steps exist
    const steps = meta.steps || [];
    if (steps.length > 0) {
        const oldTrace = div.querySelector('.stream-trace');
        if (oldTrace) oldTrace.remove();

        const stepsId = 'steps-' + Date.now();
        const stepsHtml = `
            <button class="steps-toggle" onclick="toggleSteps('${stepsId}')">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
                Show ${steps.length} steps
            </button>
            <div class="steps-detail" id="${stepsId}">
                ${steps.map(s => `
                    <div class="step-item">
                        <div class="step-header">
                            <span class="step-number">${s.stepNumber}</span>
                            <span class="step-tool">${escapeHtml(s.toolName)}</span>
                            <span class="step-duration">${s.durationMs}ms</span>
                        </div>
                        <div class="step-body">
                            <div>Input: <pre>${escapeHtml(formatJson(s.toolInput))}</pre></div>
                            <div>Output: <pre>${escapeHtml(truncate(s.toolOutput, 500))}</pre></div>
                        </div>
                    </div>
                `).join('')}
            </div>
        `;
        const wrapper = document.createElement('div');
        wrapper.innerHTML = stepsHtml;
        div.appendChild(wrapper);
    }

    $('#chatMessages').scrollTop = $('#chatMessages').scrollHeight;
}

async function sendMessageSync(message) {
    // Show typing indicator
    const typingEl = showTyping();

    try {
        const res = await fetch(API.chat, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: message,
                sessionId: state.sessionId,
            }),
        });

        typingEl.remove();

        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            appendMessage('assistant', `Error: ${err.message || res.statusText}`, null);
            toast('Request failed: ' + (err.message || res.statusText), 'error');
            return;
        }

        const data = await res.json();

        if (data.sessionId) {
            state.sessionId = data.sessionId;
            $('#sessionId').textContent = data.sessionId;
        }

        appendMessage('assistant', data.answer, data);
    } catch (err) {
        typingEl.remove();
        appendMessage('assistant', `Network error: ${err.message}`, null);
        toast('Network error', 'error');
    }
}

function appendMessage(role, content, meta) {
    const container = $('#chatMessages');
    const div = document.createElement('div');
    div.className = `message ${role}`;

    let metaHtml = '';
    let stepsHtml = '';

    if (role === 'assistant' && meta) {
        const parts = [];
        if (meta.model) parts.push(`Model: ${meta.model}`);
        if (meta.durationMs) parts.push(`${meta.durationMs}ms`);
        if (meta.totalSteps > 0) parts.push(`${meta.totalSteps} tool calls`);
        if (meta.planSummary) parts.push(meta.planSummary);
        metaHtml = `<div class="message-meta">${parts.map(p => `<span>${escapeHtml(p)}</span>`).join('')}</div>`;

        if (meta.steps && meta.steps.length > 0) {
            const stepsId = 'steps-' + Date.now();
            stepsHtml = `
                <button class="steps-toggle" onclick="toggleSteps('${stepsId}')">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
                    Show ${meta.steps.length} steps
                </button>
                <div class="steps-detail" id="${stepsId}">
                    ${meta.steps.map(s => `
                        <div class="step-item">
                            <div class="step-header">
                                <span class="step-number">${s.stepNumber}</span>
                                <span class="step-tool">${escapeHtml(s.toolName)}</span>
                                <span class="step-duration">${s.durationMs}ms</span>
                            </div>
                            <div class="step-body">
                                <div>Input: <pre>${escapeHtml(formatJson(s.toolInput))}</pre></div>
                                <div>Output: <pre>${escapeHtml(truncate(s.toolOutput, 500))}</pre></div>
                            </div>
                        </div>
                    `).join('')}
                </div>
            `;
        }
    }

    div.innerHTML = `
        <div class="message-bubble">${escapeHtml(content)}</div>
        ${metaHtml}
        ${stepsHtml}
    `;

    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}

function showTyping() {
    const container = $('#chatMessages');
    const div = document.createElement('div');
    div.className = 'typing-indicator';
    div.innerHTML = '<div class="typing-dot"></div><div class="typing-dot"></div><div class="typing-dot"></div>';
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
    return div;
}

window.toggleSteps = function (id) {
    const el = document.getElementById(id);
    if (el) el.classList.toggle('open');
};

window.toggleThinking = function (btn) {
    const panel = btn.closest('.thinking-panel');
    if (panel) panel.classList.toggle('open');
};

// ===== Knowledge Base =====
function initKnowledge() {
    // Tabs
    $$('.tabs .tab').forEach(tab => {
        tab.addEventListener('click', () => {
            const parent = tab.closest('.card');
            parent.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            parent.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            tab.classList.add('active');
            $(`#tab-${tab.dataset.tab}`).classList.add('active');
        });
    });

    // TopK slider
    $('#searchTopK').addEventListener('input', (e) => {
        $('#topKValue').textContent = e.target.value;
    });

    // Ingest
    $('#ingestBtn').addEventListener('click', ingestDocument);

    // Search
    $('#searchBtn').addEventListener('click', searchDocuments);
    $('#searchQuery').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') searchDocuments();
    });
}

async function ingestDocument() {
    const content = $('#ingestContent').value.trim();
    if (!content) {
        toast('Please enter content', 'error');
        return;
    }

    const btn = $('#ingestBtn');
    btn.disabled = true;
    btn.textContent = 'Ingesting...';

    try {
        const res = await fetch(API.ragIngest, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                content: content,
                source: $('#ingestSource').value.trim() || undefined,
                category: $('#ingestCategory').value.trim() || undefined,
            }),
        });

        const result = $('#ingestResult');
        result.classList.add('show');

        if (res.ok) {
            const data = await res.json();
            result.className = 'result-area show success';
            result.textContent = `Success: ${JSON.stringify(data)}`;
            toast('Document ingested', 'success');
        } else {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            result.className = 'result-area show error';
            result.textContent = `Error: ${err.message || res.statusText}`;
            toast('Ingestion failed', 'error');
        }
    } catch (err) {
        const result = $('#ingestResult');
        result.className = 'result-area show error';
        result.textContent = `Network error: ${err.message}`;
        toast('Network error', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Ingest Document';
    }
}

async function searchDocuments() {
    const query = $('#searchQuery').value.trim();
    if (!query) {
        toast('Please enter a search query', 'error');
        return;
    }
    const topK = $('#searchTopK').value;
    const strategy = $('#searchStrategy').value;
    const btn = $('#searchBtn');
    btn.disabled = true;
    btn.textContent = 'Searching...';

    const resultsContainer = $('#searchResults');
    resultsContainer.innerHTML = '<p class="text-muted">Searching...</p>';

    try {
        const params = new URLSearchParams({
            query,
            topK,
            strategy,
        });
        appendCsvParams(params, 'source', $('#searchSource').value);
        appendCsvParams(params, 'category', $('#searchCategory').value);
        appendCsvParams(params, 'docId', $('#searchDocIds').value);

        const url = `${API.ragSearch}?${params.toString()}`;
        const res = await fetch(url);

        if (res.ok) {
            const docs = await res.json();
            if (docs.length === 0) {
                resultsContainer.innerHTML = '<p class="text-muted">No results found.</p>';
            } else {
                resultsContainer.innerHTML = docs.map((doc, i) => {
                    const content = doc.text || doc.content || doc.formattedContent || JSON.stringify(doc);
                    const source = (doc.metadata && doc.metadata.source) || doc.source || '-';
                    const category = (doc.metadata && doc.metadata.category) || doc.category || '-';
                    const docId = (doc.metadata && doc.metadata.docId) || doc.id || '-';
                    const score = doc.score != null ? doc.score.toFixed(4) : null;
                    return `
                        <div class="search-result-item">
                            <div class="search-result-header">
                                <span class="search-result-source">#${i + 1} ${escapeHtml(source)}</span>
                                ${score ? `<span class="search-result-score">Score: ${score}</span>` : ''}
                            </div>
                            <div class="search-result-meta">Category: ${escapeHtml(category)} | Doc ID: ${escapeHtml(docId)}</div>
                            <div class="search-result-content">${escapeHtml(truncate(content, 600))}</div>
                        </div>
                    `;
                }).join('');
                toast(`Found ${docs.length} results`, 'success');
            }
        } else {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            resultsContainer.innerHTML = `<p class="text-muted" style="color:var(--danger)">Error: ${escapeHtml(err.message || res.statusText)}</p>`;
            toast('Search failed', 'error');
        }
    } catch (err) {
        resultsContainer.innerHTML = `<p class="text-muted" style="color:var(--danger)">Network error: ${err.message}</p>`;
        toast('Network error', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Search';
    }
}

function appendCsvParams(params, key, rawValue) {
    const values = rawValue
        .split(',')
        .map(value => value.trim())
        .filter(Boolean);
    values.forEach(value => params.append(key, value));
}

// ===== Dashboard =====
function initDashboard() {
    $('#refreshDashboard').addEventListener('click', refreshDashboard);
}

async function refreshDashboard() {
    // Health
    try {
        const res = await fetch(API.health);
        const data = await res.json();

        const overall = data.status === 'UP';
        updateStatusCard('healthCard', overall, data.status);

        // Component statuses
        const components = data.components || {};
        if (components.db) {
            updateStatusCard('dbCard', components.db.status === 'UP', components.db.status);
        }
        if (components.redis) {
            updateStatusCard('redisCard', components.redis.status === 'UP', components.redis.status);
        }

        $('#healthDetails').textContent = JSON.stringify(data, null, 2);
    } catch (err) {
        updateStatusCard('healthCard', false, 'Unreachable');
        updateStatusCard('dbCard', false, 'Unknown');
        updateStatusCard('redisCard', false, 'Unknown');
        $('#healthDetails').textContent = `Failed to fetch health: ${err.message}`;
    }

    // Metrics
    try {
        const res = await fetch(API.metrics);
        const data = await res.json();

        const interestingMetrics = [
            'jvm.memory.used',
            'jvm.threads.live',
            'process.uptime',
            'http.server.requests',
            'system.cpu.usage',
            'jvm.gc.pause',
        ];

        const grid = $('#metricsGrid');
        grid.innerHTML = '';

        const metricNames = data.names || [];
        const toFetch = interestingMetrics.filter(m => metricNames.includes(m));

        if (toFetch.length === 0) {
            grid.innerHTML = '<p class="text-muted">No metrics available</p>';
            return;
        }

        const results = await Promise.all(
            toFetch.map(name =>
                fetch(`${API.metrics}/${name}`)
                    .then(r => r.json())
                    .catch(() => null)
            )
        );

        results.forEach(metric => {
            if (!metric) return;
            const measurement = metric.measurements && metric.measurements[0];
            if (!measurement) return;

            let value = measurement.value;
            let displayValue;

            if (metric.name === 'process.uptime') {
                const hours = Math.floor(value / 3600);
                const mins = Math.floor((value % 3600) / 60);
                displayValue = `${hours}h ${mins}m`;
            } else if (metric.name === 'jvm.memory.used') {
                displayValue = `${(value / 1024 / 1024).toFixed(0)} MB`;
            } else if (metric.name === 'system.cpu.usage') {
                displayValue = `${(value * 100).toFixed(1)}%`;
            } else if (Number.isInteger(value)) {
                displayValue = value.toLocaleString();
            } else {
                displayValue = value.toFixed(2);
            }

            const unit = metric.baseUnit || '';

            grid.innerHTML += `
                <div class="metric-item">
                    <div class="metric-name">${escapeHtml(metric.name)}${unit ? ` (${unit})` : ''}</div>
                    <div class="metric-value">${displayValue}</div>
                </div>
            `;
        });
    } catch (err) {
        $('#metricsGrid').innerHTML = `<p class="text-muted">Failed to load metrics: ${err.message}</p>`;
    }
}

function updateStatusCard(id, isUp, text) {
    const card = document.getElementById(id);
    if (!card) return;
    const indicator = card.querySelector('.status-indicator');
    const value = card.querySelector('.status-value');
    indicator.className = `status-indicator ${isUp ? 'up' : 'down'}`;
    value.textContent = text;
}

// ===== Utilities =====
function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function formatJson(obj) {
    if (obj == null) return 'null';
    if (typeof obj === 'string') {
        try {
            return JSON.stringify(JSON.parse(obj), null, 2);
        } catch {
            return obj;
        }
    }
    return JSON.stringify(obj, null, 2);
}

function truncate(str, max) {
    if (str == null) return '';
    str = String(str);
    return str.length > max ? str.substring(0, max) + '...' : str;
}

function toast(message, type = 'info') {
    const container = $('#toastContainer');
    const div = document.createElement('div');
    div.className = `toast ${type}`;
    div.textContent = message;
    container.appendChild(div);
    setTimeout(() => div.remove(), 3000);
}

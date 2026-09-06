/* ===== Dawn AI Frontend ===== */

const API = {
    chatStream: '/api/v1/chat/stream',
    chatCapabilities: '/api/v1/chat/capabilities',
    ragIngest: '/api/v1/rag/ingest',
    ragSearch: '/api/v1/rag/search',
    ragCategories: '/api/v1/rag/categories',
    topics: '/api/v1/topics',
    health: '/actuator/health',
    metrics: '/actuator/metrics',
    aiInteractionLog: '/api/v1/ai-interactions/log',
};

// ===== Markdown setup (marked + DOMPurify, both served locally) =====
marked.use({
    gfm: true,
    breaks: true,
    renderer: {
        code(token) {
            const lang = (token.lang || '').trim();
            let code = token.text;
            if (!code && token.raw) {
                const lines = token.raw.split('\n');
                code = lines.slice(1, -1).join('\n').replace(/^ {0,3}/gm, '');
            }
            if (!code) return false;

            if (typeof hljs !== 'undefined') {
                try {
                    if (lang && hljs.getLanguage(lang)) {
                        const r = hljs.highlight(code, { language: lang, ignoreIllegals: true });
                        return `<pre><code class="hljs language-${escapeHtml(lang)}">${r.value}</code></pre>`;
                    }
                    const auto = hljs.highlightAuto(code);
                    if (auto.relevance > 4 && auto.value) {
                        return `<pre><code class="hljs language-${escapeHtml(auto.language || '')}">${auto.value}</code></pre>`;
                    }
                } catch { /* fall through */ }
            }
            const escaped = escapeHtml(code);
            const cls = lang ? ` class="language-${escapeHtml(lang)}"` : '';
            return `<pre><code${cls}>${escaped}</code></pre>`;
        },
    },
});

function renderMarkdown(text) {
    if (text == null) return '';
    const raw = String(text).replace(/\r\n?/g, '\n')
        .replace(/^(#{1,6})[^\S\n]*(?=\S)/gm, '$1 ');
    let html;
    try {
        html = marked.parse(raw);
    } catch (e) {
        console.error('marked.parse error:', e);
        return escapeHtml(raw).replace(/\n/g, '<br>');
    }
    if (typeof html !== 'string') {
        return escapeHtml(raw).replace(/\n/g, '<br>');
    }
    return DOMPurify.sanitize(html, {
        ADD_TAGS: ['pre', 'code', 'br', 'hr', 'blockquote', 'table', 'thead', 'tbody', 'tr', 'th', 'td'],
        ADD_ATTR: ['target', 'class', 'language'],
    });
}

function enhanceCodeBlocks(root) {
    if (!root) return;
    const blocks = root.querySelectorAll('pre');
    blocks.forEach(pre => {
        if (pre.parentElement && pre.parentElement.classList.contains('code-block-wrapper')) return;
        const wrapper = document.createElement('div');
        wrapper.className = 'code-block-wrapper';
        pre.parentNode.insertBefore(wrapper, pre);
        wrapper.appendChild(pre);

        const btn = document.createElement('button');
        btn.className = 'code-copy-btn';
        btn.textContent = 'Copy';
        btn.addEventListener('click', () => {
            const code = pre.querySelector('code')?.innerText ?? pre.innerText;
            navigator.clipboard.writeText(code).then(() => {
                btn.textContent = 'Copied!';
                setTimeout(() => (btn.textContent = 'Copy'), 1500);
            });
        });
        wrapper.appendChild(btn);
    });
}

// ===== Streaming render scheduler =====
// Throttles markdown re-rendering during token streaming to avoid DOM thrashing.
const streamRender = (() => {
    let scheduled = false;
    let pendingBubble = null;
    let pendingText = '';

    function isNearBottom(el) {
        if (!el) return true;
        return el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    }

    function flush() {
        scheduled = false;
        if (!pendingBubble) return;
        if (!pendingBubble.isConnected) { pendingBubble = null; pendingText = ''; return; }
        const chatMessages = $('#chatMessages');
        const shouldScroll = isNearBottom(chatMessages);
        pendingBubble.innerHTML = renderMarkdown(pendingText);
        if (shouldScroll && chatMessages) chatMessages.scrollTop = chatMessages.scrollHeight;
        pendingBubble = null;
        pendingText = '';
    }

    return {
        schedule(bubble, text) {
            pendingBubble = bubble;
            pendingText = text;
            if (!scheduled) {
                scheduled = true;
                requestAnimationFrame(flush);
            }
        },
    };
})();

// ===== Mock data for testing markdown rendering =====
const MOCK_MARKDOWN = `## Markdown 渲染测试

这是第一行
这是第二行（breaks: true 应让此处换行）
这是第三行
# 标题1
## 标题2
### 段落与强调

这是一个普通段落，包含 **加粗文本**、*斜体文本*、~~删除线~~和\`行内代码\`。

### 无序列表

- 列表项 1
- 列表项 2
  - 嵌套项 A
  - 嵌套项 B
- 列表项 3

### 有序列表

1. 第一步：初始化项目
2. 第二步：安装依赖
3. 第三步：启动服务

### 代码块

\`\`\`java
@Service
public class ChatService {
    private final OpenAiClient client;

    public String chat(String message) {
        return client.complete(message);
    }
}
\`\`\`

\`\`\`python
def fibonacci(n):
    if n <= 1:
        return n
    return fibonacci(n - 1) + fibonacci(n - 2)

print(fibonacci(10))
\`\`\`

### 引用

> 任何足够先进的技术都与魔法无异。
> —— Arthur C. Clarke

### 表格

| 特性 | 状态 | 说明 |
|------|------|------|
| 换行 | ✅ | breaks: true |
| 代码高亮 | ✅ | highlight.js |
| 表格 | ✅ | GFM tables |
| 引用 | ✅ | blockquote |

### 链接与分隔线

这是一个 [示例链接](https://example.com)。

---

渲染测试完毕。如果你能看到上面的**标题、列表、代码块、表格、引用**均有正确排版，则说明 Markdown 渲染正常。`;

// ===== Chat Storage (localStorage) =====
const SESSIONS_KEY = 'dawn-chat-sessions';
const MAX_SESSIONS = 50;

const chatStore = {
    _readIndex() {
        try { return JSON.parse(localStorage.getItem(SESSIONS_KEY)) || []; }
        catch { return []; }
    },
    _writeIndex(idx) {
        try { localStorage.setItem(SESSIONS_KEY, JSON.stringify(idx)); }
        catch { /* quota */ }
    },
    saveSession(sessionId, topicId, capabilities) {
        const idx = this._readIndex();
        if (!idx.find(s => s.id === sessionId)) {
            idx.unshift({
                id: sessionId,
                topicId: topicId || '',
                enabledTools: capabilities?.enabledTools ?? null,
                enabledSkills: capabilities?.enabledSkills ?? null,
                createdAt: Date.now(),
                preview: '',
            });
            while (idx.length > MAX_SESSIONS) {
                const old = idx.pop();
                try { localStorage.removeItem('dawn-chat-' + old.id); } catch {}
            }
            this._writeIndex(idx);
        }
    },
    updateSessionPreview(sessionId, preview) {
        const idx = this._readIndex();
        const s = idx.find(s => s.id === sessionId);
        if (s && !s.preview) {
            s.preview = String(preview).substring(0, 40);
            this._writeIndex(idx);
        }
    },
    updateSessionTopic(sessionId, topicId) {
        const idx = this._readIndex();
        const s = idx.find(s => s.id === sessionId);
        if (s) { s.topicId = topicId || ''; this._writeIndex(idx); }
    },
    updateSessionCapabilities(sessionId, capabilities) {
        const idx = this._readIndex();
        const session = idx.find(s => s.id === sessionId);
        if (!session) return;
        session.enabledTools = capabilities.enabledTools;
        session.enabledSkills = capabilities.enabledSkills;
        this._writeIndex(idx);
    },
    pushMessage(sessionId, msg) {
        const key = 'dawn-chat-' + sessionId;
        try {
            const arr = JSON.parse(localStorage.getItem(key)) || [];
            arr.push({ ...msg, timestamp: Date.now() });
            localStorage.setItem(key, JSON.stringify(arr));
        } catch { /* quota */ }
    },
    getMessages(sessionId) {
        try { return JSON.parse(localStorage.getItem('dawn-chat-' + sessionId)) || []; }
        catch { return []; }
    },
    getSessions() {
        return this._readIndex();
    },
    deleteSession(sessionId) {
        const idx = this._readIndex().filter(s => s.id !== sessionId);
        this._writeIndex(idx);
        try { localStorage.removeItem('dawn-chat-' + sessionId); } catch {}
    },
};

// ===== State =====
const state = {
    sessionId: null,
    isLoading: false,
    streamMode: true,  // default to SSE streaming
    knowledgeUploadFile: null,
    capabilityCatalog: { tools: [], skills: [] },
    enabledTools: null,
    enabledSkills: null,
};

// ===== DOM References =====
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

// ===== Init =====
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    initTheme();
    initChat();
    initSessionHistory();
    initKnowledge();
    initDashboard();
    initInteractionLogLink();
    restoreOrNewSession();
    refreshCapabilities();
    refreshTopics();
    refreshCategories();
});

// ===== Topics =====
async function refreshTopics() {
    try {
        const res = await fetch(API.topics);
        if (!res.ok) return;
        const data = await res.json();
        const topics = (data && data.topics) || [];
        const datalist = $('#allTopics');
        if (!datalist) return;
        datalist.innerHTML = topics
            .map(t => `<option value="${escapeHtml(t)}"></option>`)
            .join('');
    } catch (err) {
        // Non-blocking: topic suggestions are optional
    }
}

// ===== Categories =====
async function refreshCategories() {
    try {
        const res = await fetch(API.ragCategories);
        if (!res.ok) return;
        const categories = await res.json();
        if (!Array.isArray(categories)) return;
        const datalist = $('#ragCategories');
        if (!datalist) return;
        datalist.innerHTML = categories
            .map(c => `<option value="${escapeHtml(c)}"></option>`)
            .join('');
    } catch (err) {
        // Non-blocking: category suggestions are optional
    }
}

// ===== Session capabilities =====
async function refreshCapabilities() {
    try {
        const res = await fetch(API.chatCapabilities);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        state.capabilityCatalog = {
            tools: Array.isArray(data.tools) ? data.tools : [],
            skills: Array.isArray(data.skills) ? data.skills : [],
        };
        normalizeCapabilitySelections();
        renderCapabilitySelector();
    } catch (err) {
        const summary = $('#capabilitySummary');
        if (summary) summary.textContent = 'Server defaults';
        const tools = $('#toolCapabilityList');
        const skills = $('#skillCapabilityList');
        if (tools) tools.innerHTML = '<span class="capability-empty">Capability catalog unavailable</span>';
        if (skills) skills.innerHTML = '<span class="capability-empty">Capability catalog unavailable</span>';
    }
}

function normalizeCapabilitySelections() {
    const toolNames = new Set(state.capabilityCatalog.tools.map(item => item.name));
    const skillNames = new Set(state.capabilityCatalog.skills.map(item => item.name));
    if (Array.isArray(state.enabledTools)) {
        state.enabledTools = state.enabledTools.filter(name => toolNames.has(name));
    }
    if (Array.isArray(state.enabledSkills)) {
        state.enabledSkills = state.enabledSkills.filter(name => skillNames.has(name));
    }
}

function defaultCapabilityNames(kind) {
    return state.capabilityCatalog[kind]
        .filter(item => item.defaultEnabled)
        .map(item => item.name);
}

function enabledCapabilityNames(kind) {
    const stateKey = kind === 'tools' ? 'enabledTools' : 'enabledSkills';
    if (Array.isArray(state[stateKey])) return [...state[stateKey]];
    if (state.capabilityCatalog[kind].length === 0) return null;
    return defaultCapabilityNames(kind);
}

function currentCapabilitySelection() {
    return {
        enabledTools: enabledCapabilityNames('tools'),
        enabledSkills: enabledCapabilityNames('skills'),
    };
}

function renderCapabilitySelector() {
    renderCapabilityGroup('tools', '#toolCapabilityList');
    renderCapabilityGroup('skills', '#skillCapabilityList');
    updateCapabilitySummary();
}

function renderCapabilityGroup(kind, selector) {
    const container = $(selector);
    if (!container) return;
    const catalog = state.capabilityCatalog[kind];
    if (catalog.length === 0) {
        container.innerHTML = `<span class="capability-empty">No ${kind} available</span>`;
        return;
    }

    const selected = new Set(enabledCapabilityNames(kind) || []);
    container.innerHTML = catalog.map(item => `
        <label class="capability-option" title="${escapeHtml(item.description || '')}">
            <input type="checkbox"
                   data-capability-kind="${kind}"
                   data-capability-name="${escapeHtml(item.name)}"
                   ${selected.has(item.name) ? 'checked' : ''}>
            <span>
                <strong>${escapeHtml(item.name)}</strong>
                <small>${escapeHtml(item.description || '')}</small>
            </span>
        </label>
    `).join('');

    container.querySelectorAll('input[type="checkbox"]').forEach(input => {
        input.addEventListener('change', () => {
            const stateKey = kind === 'tools' ? 'enabledTools' : 'enabledSkills';
            state[stateKey] = [...container.querySelectorAll('input[type="checkbox"]:checked')]
                .map(item => item.dataset.capabilityName);
            const selection = currentCapabilitySelection();
            chatStore.updateSessionCapabilities(state.sessionId, selection);
            updateCapabilitySummary();
        });
    });
}

function updateCapabilitySummary() {
    const summary = $('#capabilitySummary');
    if (!summary) return;
    const tools = enabledCapabilityNames('tools');
    const skills = enabledCapabilityNames('skills');
    if (tools == null || skills == null) {
        summary.textContent = 'Server defaults';
        return;
    }
    summary.textContent = `${tools.length} tools · ${skills.length} skills`;
}

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
    syncHljsTheme();

    $('#themeToggle').addEventListener('click', () => {
        const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
        if (isDark) {
            document.documentElement.removeAttribute('data-theme');
            localStorage.setItem('dawn-theme', 'light');
        } else {
            document.documentElement.setAttribute('data-theme', 'dark');
            localStorage.setItem('dawn-theme', 'dark');
        }
        syncHljsTheme();
    });
}

function syncHljsTheme() {
    const dark = document.documentElement.getAttribute('data-theme') === 'dark';
    const lightSheet = document.getElementById('hljs-light-theme');
    const darkSheet = document.getElementById('hljs-dark-theme');
    if (lightSheet) lightSheet.disabled = dark;
    if (darkSheet) darkSheet.disabled = !dark;
}

// ===== Session History =====
function initSessionHistory() {
    const clearBtn = $('#clearHistoryBtn');
    if (clearBtn) {
        clearBtn.addEventListener('click', () => {
            if (!confirm('清除全部历史对话？')) return;
            chatStore.getSessions().forEach(s => chatStore.deleteSession(s.id));
            newSession();
            toast('History cleared', 'info');
        });
    }
    renderSessionList();
}

function renderSessionList() {
    const list = $('#sessionHistoryList');
    if (!list) return;
    const sessions = chatStore.getSessions();
    if (sessions.length === 0) {
        list.innerHTML = '<div style="padding:8px 10px;font-size:12px;color:var(--text-tertiary)">No history yet</div>';
        return;
    }
    list.innerHTML = sessions.map(s => {
        const isActive = s.id === state.sessionId;
        const preview = s.preview || s.topicId || s.id;
        const timeStr = formatRelativeTime(s.createdAt);
        return `<div class="session-item${isActive ? ' active' : ''}" data-sid="${escapeHtml(s.id)}">
            <span class="session-item-preview">${escapeHtml(preview)}</span>
            <span class="session-item-time">${escapeHtml(timeStr)}</span>
            <button class="session-item-delete" data-delete="${escapeHtml(s.id)}" title="Delete">&times;</button>
        </div>`;
    }).join('');

    list.querySelectorAll('.session-item').forEach(el => {
        el.addEventListener('click', (e) => {
            if (e.target.closest('.session-item-delete')) return;
            loadSession(el.dataset.sid);
        });
    });
    list.querySelectorAll('.session-item-delete').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const sid = btn.dataset.delete;
            chatStore.deleteSession(sid);
            if (sid === state.sessionId) {
                const remaining = chatStore.getSessions();
                if (remaining.length > 0) {
                    loadSession(remaining[0].id);
                } else {
                    newSession();
                }
            } else {
                renderSessionList();
            }
        });
    });
}

function loadSession(sessionId) {
    state.isLoading = false;
    $('#sendBtn').disabled = false;
    const sessions = chatStore.getSessions();
    const sessionMeta = sessions.find(s => s.id === sessionId);
    state.sessionId = sessionId;
    $('#sessionId').textContent = sessionId;

    const topicEl = $('#chatTopic');
    if (topicEl) topicEl.value = sessionMeta?.topicId || '';
    state.enabledTools = Array.isArray(sessionMeta?.enabledTools)
        ? [...sessionMeta.enabledTools]
        : null;
    state.enabledSkills = Array.isArray(sessionMeta?.enabledSkills)
        ? [...sessionMeta.enabledSkills]
        : null;
    normalizeCapabilitySelections();
    renderCapabilitySelector();

    const container = $('#chatMessages');
    container.innerHTML = '';

    const messages = chatStore.getMessages(sessionId);
    messages.forEach(msg => {
        appendMessage(msg.role, msg.content, msg.meta || null);
    });

    if (messages.length === 0) {
        container.innerHTML = `
            <div class="welcome-message">
                <h3>Dawn AI Agent Workbench</h3>
                <p>发起对话，观察 Agent 的计划、工具调用、记忆与检索链路。</p>
            </div>
        `;
    }

    updateInteractionLogLink();
    renderSessionList();

    const navChat = document.querySelector('.nav-item[data-page="chat"]');
    if (navChat && !navChat.classList.contains('active')) {
        navChat.click();
    }
}

function formatRelativeTime(ts) {
    const diff = Date.now() - ts;
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + ' min ago';
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + 'h ago';
    const days = Math.floor(hours / 24);
    if (days < 30) return days + 'd ago';
    return new Date(ts).toLocaleDateString();
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

    // Send with Cmd/Ctrl+Enter; plain Enter keeps editing in the textarea.
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && !e.isComposing) {
            e.preventDefault();
            sendMessage();
        }
    });

    sendBtn.addEventListener('click', sendMessage);
    $('#newSessionBtn').addEventListener('click', () => {
        newSession();
        toast('New session created', 'info');
    });

    const testBtn = $('#testMarkdownBtn');
    if (testBtn) {
        testBtn.addEventListener('click', () => {
            const welcome = $('#chatMessages .welcome-message');
            if (welcome) welcome.remove();
            appendMessage('user', '测试 Markdown 渲染');
            appendMessage('assistant', MOCK_MARKDOWN, {
                model: 'mock',
                durationMs: 42,
                totalSteps: 0,
            });
            toast('Mock markdown 已注入', 'info');
        });
    }
}

function getChatTopicId() {
    const topicEl = $('#chatTopic');
    const value = topicEl ? topicEl.value.trim() : '';
    return value || undefined;
}

function restoreOrNewSession() {
    const sessions = chatStore.getSessions();
    if (sessions.length > 0) {
        loadSession(sessions[0].id);
    } else {
        newSession();
    }
}

function newSession() {
    state.isLoading = false;
    $('#sendBtn').disabled = false;
    state.sessionId = 'session-' + Date.now().toString(36);
    $('#sessionId').textContent = state.sessionId;
    const topicEl = $('#chatTopic');
    if (topicEl) topicEl.value = '';
    state.enabledTools = null;
    state.enabledSkills = null;
    renderCapabilitySelector();

    const messages = $('#chatMessages');
    messages.innerHTML = `
        <div class="welcome-message">
            <h3>Dawn AI Agent Workbench</h3>
            <p>发起对话，观察 Agent 的计划、工具调用、记忆与检索链路。</p>
        </div>
    `;
    updateInteractionLogLink();
    renderSessionList();
}

async function sendMessage() {
    const input = $('#chatInput');
    const message = input.value.trim();
    if (!message || state.isLoading) return;

    state.isLoading = true;
    $('#sendBtn').disabled = true;

    const requestSessionId = state.sessionId;
    const requestTopicId = getChatTopicId();
    const requestCapabilities = currentCapabilitySelection();

    // Remove welcome message
    const welcome = $('#chatMessages .welcome-message');
    if (welcome) welcome.remove();

    // Add user message
    appendMessage('user', message);
    chatStore.saveSession(requestSessionId, requestTopicId, requestCapabilities);
    chatStore.updateSessionCapabilities(requestSessionId, requestCapabilities);
    chatStore.pushMessage(requestSessionId, { role: 'user', content: message });
    chatStore.updateSessionPreview(requestSessionId, message);
    renderSessionList();
    input.value = '';
    input.style.height = 'auto';

    if (state.streamMode) {
        await sendMessageStream(
            message,
            requestSessionId,
            requestTopicId,
            requestCapabilities);
    } else {
        await sendMessageSync(
            message,
            requestSessionId,
            requestTopicId,
            requestCapabilities);
    }

    if (state.sessionId === requestSessionId) {
        state.isLoading = false;
        $('#sendBtn').disabled = false;
        $('#chatInput').focus();
    }
}

async function sendMessageStream(message, requestSessionId, requestTopicId, requestCapabilities) {
    const typingEl = showTyping();
    const assistantDiv = createAssistantPlaceholder();
    let accumulatedContent = '';

    try {
        const res = await fetch(API.chatStream, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message,
                sessionId: requestSessionId,
                topicId: requestTopicId,
                enabledTools: requestCapabilities.enabledTools,
                enabledSkills: requestCapabilities.enabledSkills,
            }),
        });

        if (typingEl.isConnected) typingEl.remove();

        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            if (assistantDiv.isConnected) {
                assistantDiv.querySelector('.message-bubble').textContent = `Error: ${err.message || res.statusText}`;
            }
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
            buffer = lines.pop();

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    eventName = line.slice(6).trim();
                } else if (line.startsWith('data:')) {
                    const raw = line.slice(5).trim();
                    if (!raw) continue;
                    try {
                        const envelope = JSON.parse(raw);
                        if (envelope.event === 'token') {
                            accumulatedContent += (envelope.data && envelope.data.content) || '';
                        }
                        if (assistantDiv.isConnected) {
                            handleStreamEvent(eventName || envelope.event, envelope, assistantDiv);
                        }
                        if (envelope.event === 'done') streamMeta = envelope.data;
                    } catch (e) { /* skip */ }
                    eventName = '';
                } else if (line === '') {
                    eventName = '';
                }
            }
        }

        if (streamMeta) {
            if (assistantDiv.isConnected) {
                finaliseAssistantMessage(assistantDiv, streamMeta, requestSessionId);
            } else {
                chatStore.pushMessage(requestSessionId, {
                    role: 'assistant',
                    content: accumulatedContent,
                    meta: { model: streamMeta.model, durationMs: streamMeta.durationMs, totalSteps: streamMeta.totalSteps, steps: streamMeta.steps },
                });
            }
        }

    } catch (err) {
        if (typingEl.isConnected) typingEl.remove();
        if (err.name === 'AbortError') return;
        if (assistantDiv.isConnected) {
            assistantDiv.querySelector('.message-bubble').textContent = `Network error: ${err.message}`;
        }
        if (accumulatedContent) {
            chatStore.pushMessage(requestSessionId, {
                role: 'assistant', content: accumulatedContent, meta: { error: err.message },
            });
        }
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
            finalizePlanThinking(assistantDiv);
            const thinkingPanel = getOrCreateThinkingPanel(
                assistantDiv,
                'answer-thinking-panel',
                '思考中...'
            );
            thinkingPanel.querySelector('.thinking-content').textContent += data.content || '';
            break;
        }
        case 'token': {
            finalizePlanThinking(assistantDiv);
            const thinkingPanel = assistantDiv.querySelector('.answer-thinking-panel');
            if (thinkingPanel) {
                thinkingPanel.querySelector('.thinking-label').textContent = '已思考';
                thinkingPanel.classList.add('done');
            }
            const bubble = assistantDiv.querySelector('.message-bubble');
            const prev = bubble.dataset.rawContent || '';
            const next = prev + (data.content || '');
            bubble.dataset.rawContent = next;
            streamRender.schedule(bubble, next);
            break;
        }
        case 'step': {
            finalizePlanThinking(assistantDiv);
            let tracePanel = assistantDiv.querySelector('.stream-trace');
            if (!tracePanel) {
                tracePanel = document.createElement('div');
                tracePanel.className = 'stream-trace';
                assistantDiv.appendChild(tracePanel);
            }
            const statusMeta = {
                success: { className: 'success', label: '成功' },
                empty: { className: 'empty', label: '无结果' },
                retryable_failure: { className: 'failure', label: '可重试失败' },
                permanent_failure: { className: 'failure', label: '失败' },
                refused: { className: 'refused', label: '已拒绝' },
                partial: { className: 'partial', label: '部分完成' },
            }[data.status] || { className: 'unknown', label: data.status || '未知' };
            const stepEl = document.createElement('div');
            stepEl.className = 'step-item';
            stepEl.innerHTML = `
                <div class="step-header">
                    <span class="step-number">${data.stepNumber}</span>
                    <span class="step-tool">${escapeHtml(data.toolName || '')}</span>
                    <span class="step-status step-status-${statusMeta.className}">${escapeHtml(statusMeta.label)}</span>
                    <span class="step-duration">${data.durationMs || 0}ms</span>
                </div>
            `;
            tracePanel.appendChild(stepEl);
            break;
        }
        case 'plan': {
            finalizePlanThinking(assistantDiv);
            let planEl = assistantDiv.querySelector('.stream-plan');
            if (!planEl) {
                planEl = document.createElement('div');
                planEl.className = 'stream-plan';
                assistantDiv.insertBefore(planEl, assistantDiv.querySelector('.message-bubble'));
            }
            const planSteps = data.steps || [];
            if (planSteps.length > 0) {
                const planId = nextStepsId();
                planEl.innerHTML = `
                    <button class="steps-toggle" onclick="toggleSteps('${planId}')">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
                        规划 ${planSteps.length} 步
                    </button>
                    <div class="steps-detail" id="${planId}">
                        ${planSteps.map(s => `
                            <div class="step-item">
                                <div class="step-header">
                                    <span class="step-number">${s.step}</span>
                                    <span class="step-tool">${escapeHtml(s.action || '')}</span>
                                </div>
                                <div class="step-body">${escapeHtml(s.reason || '')}</div>
                            </div>
                        `).join('')}
                    </div>
                `;
            } else {
                planEl.textContent = data.summary || '';
            }
            break;
        }
        case 'error': {
            const bubble = assistantDiv.querySelector('.message-bubble');
            bubble.textContent = `[${data.code}] ${data.message}`;
            bubble.dataset.rawContent = `[${data.code}] ${data.message}`;
            break;
        }
    }
}

function finalizePlanThinking(assistantDiv) {
    const planThinkingPanel = assistantDiv.querySelector('.plan-thinking-panel');
    if (planThinkingPanel && !planThinkingPanel.classList.contains('done')) {
        planThinkingPanel.querySelector('.thinking-label').textContent = '已完成规划';
        planThinkingPanel.classList.add('done');
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

// Monotonic counter for unique steps-panel ids. Date.now() collides when
// loadSession() re-renders multiple messages synchronously in the same ms,
// which made getElementById() target the wrong (first) panel.
let stepsPanelSeq = 0;
function nextStepsId() {
    return 'steps-' + (++stepsPanelSeq);
}

function finaliseAssistantMessage(div, meta, requestSessionId) {
    const sid = requestSessionId || state.sessionId;
    const bubble = div.querySelector('.message-bubble');
    if (bubble && bubble.dataset.rawContent) {
        bubble.innerHTML = renderMarkdown(bubble.dataset.rawContent);
        enhanceCodeBlocks(bubble);
        chatStore.pushMessage(sid, {
            role: 'assistant',
            content: bubble.dataset.rawContent,
            meta: { model: meta.model, durationMs: meta.durationMs, totalSteps: meta.totalSteps, steps: meta.steps },
        });
    }

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

        const stepsId = nextStepsId();
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

async function sendMessageSync(message, requestSessionId, requestTopicId, requestCapabilities) {
    const typingEl = showTyping();

    try {
        const res = await fetch(API.chat, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: message,
                sessionId: requestSessionId,
                topicId: requestTopicId,
                enabledTools: requestCapabilities.enabledTools,
                enabledSkills: requestCapabilities.enabledSkills,
            }),
        });

        if (typingEl.isConnected) typingEl.remove();

        if (!res.ok) {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            if (state.sessionId === requestSessionId) {
                appendMessage('assistant', `Error: ${err.message || res.statusText}`, null);
            }
            toast('Request failed: ' + (err.message || res.statusText), 'error');
            return;
        }

        const data = await res.json();

        if (state.sessionId === requestSessionId) {
            appendMessage('assistant', data.answer, data);
        }
        chatStore.pushMessage(requestSessionId, {
            role: 'assistant',
            content: data.answer,
            meta: { model: data.model, durationMs: data.durationMs, totalSteps: data.totalSteps, steps: data.steps },
        });
    } catch (err) {
        if (typingEl.isConnected) typingEl.remove();
        if (state.sessionId === requestSessionId) {
            appendMessage('assistant', `Network error: ${err.message}`, null);
        }
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
            const stepsId = nextStepsId();
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

    const bubbleHtml = role === 'assistant'
        ? renderMarkdown(content)
        : escapeHtml(content);

    div.innerHTML = `
        <div class="message-bubble" data-raw-content="${escapeHtml(content || '')}">${bubbleHtml}</div>
        ${metaHtml}
        ${stepsHtml}
    `;

    container.appendChild(div);
    if (role === 'assistant') {
        enhanceCodeBlocks(div.querySelector('.message-bubble'));
    }
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

    // Ingest text
    $('#ingestBtn').addEventListener('click', ingestDocument);

    // File upload
    initFileUpload();

    // Search
    $('#searchBtn').addEventListener('click', searchDocuments);
    $('#searchQuery').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') searchDocuments();
    });
}

function initFileUpload() {
    const zone = $('#fileUploadZone');
    const fileInput = $('#fileInput');
    const uploadBtn = $('#uploadBtn');

    const openPicker = () => fileInput.click();

    zone.addEventListener('click', openPicker);
    zone.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            openPicker();
        }
    });

    fileInput.addEventListener('change', () => {
        setSelectedKnowledgeFile(fileInput.files[0] || null);
    });

    zone.addEventListener('dragenter', (e) => {
        e.preventDefault();
        zone.classList.add('drag-over');
    });
    zone.addEventListener('dragover', (e) => {
        e.preventDefault();
        zone.classList.add('drag-over');
    });
    zone.addEventListener('dragleave', (e) => {
        if (e.currentTarget.contains(e.relatedTarget)) return;
        zone.classList.remove('drag-over');
    });
    zone.addEventListener('drop', (e) => {
        e.preventDefault();
        zone.classList.remove('drag-over');
        setSelectedKnowledgeFile((e.dataTransfer && e.dataTransfer.files[0]) || null);
    });

    $('#fileDocumentType').addEventListener('change', updateJsonlFieldVisibility);

    uploadBtn.addEventListener('click', ingestFile);
}

// Show the JSONL text-field input only when the upload will be treated as JSONL:
// either explicitly chosen, or auto-detected from a .jsonl/.ndjson filename.
function updateJsonlFieldVisibility() {
    const group = $('#jsonlFieldGroup');
    if (!group) return;
    const docType = $('#fileDocumentType').value;
    const file = state.knowledgeUploadFile;
    const nameIsJsonl = file && /\.(jsonl|ndjson)$/i.test(file.name);
    const isJsonl = docType === 'JSONL' || (docType === '' && nameIsJsonl);
    group.style.display = isJsonl ? '' : 'none';
}

function setSelectedKnowledgeFile(file) {
    const nameEl = $('#fileSelectedName');
    const uploadBtn = $('#uploadBtn');
    const zone = $('#fileUploadZone');

    state.knowledgeUploadFile = file;
    zone.classList.toggle('has-file', Boolean(file));

    if (file) {
        nameEl.textContent = `${file.name} (${formatFileSize(file.size)})`;
        uploadBtn.disabled = false;
    } else {
        nameEl.textContent = '';
        uploadBtn.disabled = true;
        $('#fileInput').value = '';
    }

    updateJsonlFieldVisibility();
}

function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

async function ingestFile() {
    const file = state.knowledgeUploadFile;
    if (!file) {
        toast('Please select a file first', 'error');
        return;
    }

    const btn = $('#uploadBtn');
    btn.disabled = true;
    btn.textContent = 'Uploading...';

    const formData = new FormData();
    formData.append('file', file);

    const docType = $('#fileDocumentType').value;
    if (docType) formData.append('documentType', docType);

    const source = $('#fileSource').value.trim();
    if (source) formData.append('source', source);

    const category = $('#fileCategory').value.trim();
    if (category) formData.append('category', category);

    const topicId = $('#fileTopic').value.trim();
    if (topicId) formData.append('topicId', topicId);

    // Only meaningful for JSONL uploads; backend ignores it for other types.
    const textField = $('#fileTextField').value.trim();
    if (textField) formData.append('textField', textField);

    const result = $('#uploadResult');
    result.classList.add('show');
    result.className = 'result-area show';
    result.textContent = 'Uploading and parsing file...';

    try {
        const res = await fetch(API.ragIngest, {
            method: 'POST',
            body: formData,
            // Do NOT set Content-Type – browser sets it with boundary automatically
        });

        if (res.ok) {
            const data = await res.json();
            result.className = 'result-area show success';
            result.textContent = `Success: ${JSON.stringify(data)}`;
            toast('File ingested successfully', 'success');
            refreshTopics();
            // Reset
            setSelectedKnowledgeFile(null);
        } else {
            const err = await res.json().catch(() => ({ message: res.statusText }));
            result.className = 'result-area show error';
            result.textContent = `Error: ${err.message || res.statusText}`;
            toast('Upload failed', 'error');
        }
    } catch (err) {
        result.className = 'result-area show error';
        result.textContent = `Network error: ${err.message}`;
        toast('Network error', 'error');
    } finally {
        btn.disabled = !state.knowledgeUploadFile;
        btn.textContent = 'Upload & Ingest';
    }
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
                topicId: $('#ingestTopic').value.trim() || undefined,
            }),
        });

        const result = $('#ingestResult');
        result.classList.add('show');

        if (res.ok) {
            const data = await res.json();
            result.className = 'result-area show success';
            result.textContent = `Success: ${JSON.stringify(data)}`;
            toast('Document ingested', 'success');
            refreshTopics();
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
        appendCsvParams(params, 'topicId', $('#searchTopic').value);

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
                    <div class="metric-name">
                        ${escapeHtml(formatMetricName(metric.name))}
                        ${unit ? `<span class="metric-unit">(${escapeHtml(unit)})</span>` : ''}
                    </div>
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

function formatMetricName(name) {
    return String(name || '').replace(/\./g, ' · ');
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

// ===== AI Interaction Log link =====
function initInteractionLogLink() {
    updateInteractionLogLink();
}

function updateInteractionLogLink() {
    const link = $('#interactionsToggle');
    if (!link) return;
    const sid = state.sessionId;
    const params = new URLSearchParams({ tail: '1000', pretty: 'true' });
    if (sid) params.set('sessionId', sid);
    link.href = `${API.aiInteractionLog}?${params.toString()}`;
}

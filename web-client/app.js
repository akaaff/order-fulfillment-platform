// Demo client - talks only to api-gateway (never a backend service directly).
// No build step on purpose: this is meant to be a day of work, not a second
// stack to maintain (see CLAUDE.md).
//
// Local dev serves this on :8085 via a plain static file server, with
// api-gateway separately on :8080 - different origin, real CORS. Behind the
// minikube Ingress, web-client and api-gateway sit on the same host under
// different paths (/, /api, /auth) - same origin, so the gateway base is
// just empty and every call becomes a same-origin relative path.
const GATEWAY = location.port === "8085" ? "http://localhost:8080" : "";
const KNOWN_SKUS = ["SKU-WIDGET", "SKU-GADGET", "SKU-GIZMO"];

const ICONS = {
    check: '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="1.6"/><path d="m8 12.5 2.5 2.5L16 9.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    warning: '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3 2 20h20L12 3Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M12 10v4M12 17h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
};

const els = {
    loginSection: document.getElementById("login-section"),
    appSection: document.getElementById("app-section"),
    sessionChip: document.getElementById("session-chip"),
    customerSelect: document.getElementById("customer-select"),
    loginBtn: document.getElementById("login-btn"),
    loginStatus: document.getElementById("login-status"),
    currentCustomer: document.getElementById("current-customer"),
    logoutBtn: document.getElementById("logout-btn"),
    orderLines: document.getElementById("order-lines"),
    addLineBtn: document.getElementById("add-line-btn"),
    submitOrderBtn: document.getElementById("submit-order-btn"),
    orderResult: document.getElementById("order-result"),
    statusFilter: document.getElementById("status-filter"),
    refreshOrdersBtn: document.getElementById("refresh-orders-btn"),
    ordersTableBody: document.querySelector("#orders-table tbody"),
    chatLog: document.getElementById("chat-log"),
    chatInput: document.getElementById("chat-input"),
    chatSendBtn: document.getElementById("chat-send-btn"),
};

function getToken() {
    return sessionStorage.getItem("token");
}

function getCustomerId() {
    return sessionStorage.getItem("customerId");
}

function setSession(token, customerId) {
    sessionStorage.setItem("token", token);
    sessionStorage.setItem("customerId", customerId);
}

function clearSession() {
    sessionStorage.removeItem("token");
    sessionStorage.removeItem("customerId");
}

// Toggles a button between its resting state and a disabled, spinner-plus-text
// "busy" state - used for every button that fires a fetch, so a slow request
// (or a slow local Ollama model) reads as "working", not "did my click land?"
function setBusy(button, busy, busyLabel) {
    const label = button.querySelector(".btn-label") || button;
    if (busy) {
        button.dataset.restingLabel = label.textContent;
        label.innerHTML = `<span class="spinner"></span> ${busyLabel}`;
        button.disabled = true;
    } else {
        label.textContent = button.dataset.restingLabel || label.textContent;
        button.disabled = false;
    }
}

function showApp() {
    els.loginSection.hidden = true;
    els.appSection.hidden = false;
    els.sessionChip.hidden = false;
    els.currentCustomer.textContent = getCustomerId();
    if (els.orderLines.children.length === 0) {
        addOrderLine();
    }
    refreshOrders();
}

function showLogin() {
    els.loginSection.hidden = false;
    els.appSection.hidden = true;
    els.sessionChip.hidden = true;
    els.loginStatus.textContent = "";
}

// The orders table already gets overwritten by refreshOrders() on the next
// login, but the chat log only ever appends and the order-result box only
// ever gets overwritten by a new "place order" click - without this, a
// previous customer's chat transcript and last order confirmation stay
// visible after switching customers, even though every underlying API call
// is correctly scoped to whichever customer is actually logged in.
function resetSessionUiState() {
    els.chatLog.innerHTML = "";
    els.orderResult.innerHTML = "";
    els.ordersTableBody.innerHTML = "";
}

// Every call to a protected route goes through here so the bearer token and
// the "session expired, log out" handling only live in one place.
async function authedFetch(path, options = {}) {
    const headers = Object.assign({}, options.headers, {
        Authorization: `Bearer ${getToken()}`,
    });
    const response = await fetch(`${GATEWAY}${path}`, Object.assign({}, options, {headers}));
    if (response.status === 401) {
        clearSession();
        resetSessionUiState();
        showLogin();
        els.loginStatus.textContent = "Session expired - please log in again.";
        els.loginStatus.className = "status error";
        throw new Error("401 Unauthorized");
    }
    return response;
}

async function login() {
    const customerId = els.customerSelect.value;
    els.loginStatus.textContent = "";
    setBusy(els.loginBtn, true, "Logging in...");

    try {
        const response = await fetch(`${GATEWAY}/auth/login`, {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({customerId}),
        });

        if (!response.ok) {
            els.loginStatus.textContent = `Login failed (HTTP ${response.status}). Unknown demo customer?`;
            els.loginStatus.className = "status error";
            return;
        }

        const data = await response.json();
        setSession(data.token, data.customerId);
        showApp();
    } finally {
        setBusy(els.loginBtn, false);
    }
}

function logout() {
    clearSession();
    resetSessionUiState();
    showLogin();
}

function addOrderLine() {
    const row = document.createElement("div");
    row.className = "order-line-row";

    const skuSelect = document.createElement("select");
    skuSelect.className = "line-sku";
    KNOWN_SKUS.forEach((sku) => {
        const option = document.createElement("option");
        option.value = sku;
        option.textContent = sku;
        skuSelect.appendChild(option);
    });

    const qtyInput = document.createElement("input");
    qtyInput.type = "number";
    qtyInput.className = "line-qty";
    qtyInput.min = "1";
    qtyInput.value = "1";

    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "btn btn-ghost btn-sm";
    removeBtn.textContent = "Remove";
    removeBtn.addEventListener("click", () => row.remove());

    row.append(skuSelect, qtyInput, removeBtn);
    els.orderLines.appendChild(row);
}

function collectOrderLines() {
    return Array.from(els.orderLines.querySelectorAll(".order-line-row")).map((row) => ({
        sku: row.querySelector(".line-sku").value,
        quantity: Number(row.querySelector(".line-qty").value),
    }));
}

function renderOrderBanner(kind, title, detailHtml, raw) {
    const icon = kind === "success" ? ICONS.check : ICONS.warning;
    els.orderResult.innerHTML = `
        <div class="result-banner ${kind}">
            <span class="result-icon">${icon}</span>
            <div class="result-body">
                <div class="result-title${kind === "error" ? " error-text" : ""}">${title}</div>
                <div class="result-detail">${detailHtml}</div>
                ${raw ? `<details class="result-raw"><summary>View raw response</summary><pre>${raw}</pre></details>` : ""}
            </div>
        </div>`;
}

async function placeOrder() {
    const lines = collectOrderLines();
    if (lines.length === 0) {
        renderOrderBanner("error", "Nothing to submit", "Add at least one order line first.");
        return;
    }

    setBusy(els.submitOrderBtn, true, "Placing order...");
    try {
        const response = await authedFetch("/api/orders", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({lines}),
        });
        const data = await response.json();

        if (!response.ok) {
            renderOrderBanner("error", `Order rejected (HTTP ${response.status})`, data.error || data.message || "See raw response for details.", JSON.stringify(data, null, 2));
            return;
        }

        const skuSummary = data.lines.map((l) => `${l.quantity}× ${l.sku}`).join(", ");
        renderOrderBanner(
            "success",
            "Order placed",
            `<code>${data.orderId}</code> - ${skuSummary} - status <span class="badge ${data.status}">${data.status}</span>`,
            JSON.stringify(data, null, 2),
        );
        refreshOrders();
    } catch (e) {
        // authedFetch already surfaced a 401 to the login screen.
    } finally {
        setBusy(els.submitOrderBtn, false);
    }
}

function renderOrdersTable(orders) {
    els.ordersTableBody.innerHTML = "";

    if (orders.length === 0) {
        const tr = document.createElement("tr");
        tr.className = "empty-row";
        const td = document.createElement("td");
        td.colSpan = 5;
        td.textContent = "No orders yet - place one above to see it here.";
        tr.appendChild(td);
        els.ordersTableBody.appendChild(tr);
        return;
    }

    orders.forEach((order) => {
        const tr = document.createElement("tr");

        const idCell = document.createElement("td");
        idCell.className = "order-id-cell";
        idCell.textContent = order.orderId;

        const statusCell = document.createElement("td");
        const badge = document.createElement("span");
        badge.className = `badge ${order.status}`;
        badge.textContent = order.status;
        statusCell.appendChild(badge);

        const skuCell = document.createElement("td");
        skuCell.textContent = (order.skus || []).join(", ");

        const createdCell = document.createElement("td");
        createdCell.textContent = new Date(order.createdAt).toLocaleString();

        const reasonCell = document.createElement("td");
        reasonCell.textContent = order.cancellationReason || "—";

        tr.append(idCell, statusCell, skuCell, createdCell, reasonCell);
        els.ordersTableBody.appendChild(tr);
    });
}

async function refreshOrders() {
    const status = els.statusFilter.value;
    const qs = status ? `?status=${encodeURIComponent(status)}` : "";
    try {
        const response = await authedFetch(`/api/orders/search${qs}`);
        const orders = await response.json();
        renderOrdersTable(orders);
    } catch (e) {
        // authedFetch already surfaced a 401 to the login screen.
    }
}

function appendChatMessage(who, text) {
    const bubble = document.createElement("div");
    bubble.className = `chat-message ${who === "You" ? "you" : "assistant"}`;
    bubble.textContent = text;
    els.chatLog.appendChild(bubble);
    els.chatLog.scrollTop = els.chatLog.scrollHeight;
    return bubble;
}

function appendThinkingBubble() {
    const bubble = document.createElement("div");
    bubble.className = "chat-message assistant thinking";
    bubble.innerHTML = '<span class="spinner"></span> Thinking...';
    els.chatLog.appendChild(bubble);
    els.chatLog.scrollTop = els.chatLog.scrollHeight;
    return bubble;
}

async function sendChatMessage() {
    const question = els.chatInput.value.trim();
    if (!question) {
        return;
    }
    appendChatMessage("You", question);
    els.chatInput.value = "";
    els.chatSendBtn.disabled = true;
    const thinkingBubble = appendThinkingBubble();

    try {
        const response = await authedFetch("/api/assistant/ask", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({question}),
        });
        thinkingBubble.remove();
        if (!response.ok) {
            appendChatMessage("Assistant", `(error ${response.status} - the assistant may be unavailable)`);
            return;
        }
        const data = await response.json();
        appendChatMessage("Assistant", data.answer);
    } catch (e) {
        thinkingBubble.remove();
        // authedFetch already surfaced a 401 to the login screen.
    } finally {
        els.chatSendBtn.disabled = false;
    }
}

els.loginBtn.addEventListener("click", login);
els.logoutBtn.addEventListener("click", logout);
els.addLineBtn.addEventListener("click", addOrderLine);
els.submitOrderBtn.addEventListener("click", placeOrder);
els.refreshOrdersBtn.addEventListener("click", refreshOrders);
els.statusFilter.addEventListener("change", refreshOrders);
els.chatSendBtn.addEventListener("click", sendChatMessage);
els.chatInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
        sendChatMessage();
    }
});

if (getToken()) {
    showApp();
} else {
    showLogin();
}

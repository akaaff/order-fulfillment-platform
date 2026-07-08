// Demo client - talks only to api-gateway (never a backend service directly).
// No build step on purpose: this is meant to be a day of work, not a second
// stack to maintain (see CLAUDE.md).
const GATEWAY = "http://localhost:8080";
const KNOWN_SKUS = ["SKU-WIDGET", "SKU-GADGET", "SKU-GIZMO"];

const els = {
    loginSection: document.getElementById("login-section"),
    appSection: document.getElementById("app-section"),
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

function showApp() {
    els.loginSection.hidden = true;
    els.appSection.hidden = false;
    els.currentCustomer.textContent = getCustomerId();
    if (els.orderLines.children.length === 0) {
        addOrderLine();
    }
    refreshOrders();
}

function showLogin() {
    els.loginSection.hidden = false;
    els.appSection.hidden = true;
    els.loginStatus.textContent = "";
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
        showLogin();
        els.loginStatus.textContent = "Session expired - please log in again.";
        els.loginStatus.className = "status error";
        throw new Error("401 Unauthorized");
    }
    return response;
}

async function login() {
    const customerId = els.customerSelect.value;
    els.loginStatus.textContent = "Logging in...";
    els.loginStatus.className = "status";

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
}

function logout() {
    clearSession();
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
    removeBtn.className = "secondary";
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

async function placeOrder() {
    const lines = collectOrderLines();
    if (lines.length === 0) {
        els.orderResult.textContent = "Add at least one line first.";
        return;
    }

    els.orderResult.textContent = "Placing order...";
    try {
        const response = await authedFetch("/api/orders", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({lines}),
        });
        const data = await response.json();
        els.orderResult.textContent = JSON.stringify(data, null, 2);
        refreshOrders();
    } catch (e) {
        // authedFetch already surfaced a 401 to the login screen.
    }
}

function renderOrdersTable(orders) {
    els.ordersTableBody.innerHTML = "";
    orders.forEach((order) => {
        const tr = document.createElement("tr");

        const idCell = document.createElement("td");
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
        reasonCell.textContent = order.cancellationReason || "";

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
}

async function sendChatMessage() {
    const question = els.chatInput.value.trim();
    if (!question) {
        return;
    }
    appendChatMessage("You", question);
    els.chatInput.value = "";

    try {
        const response = await authedFetch("/api/assistant/ask", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({question}),
        });
        if (!response.ok) {
            appendChatMessage("Assistant", `(error ${response.status} - the assistant may be unavailable)`);
            return;
        }
        const data = await response.json();
        appendChatMessage("Assistant", data.answer);
    } catch (e) {
        // authedFetch already surfaced a 401 to the login screen.
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

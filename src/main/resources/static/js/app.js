const $ = (sel, el = document) => el.querySelector(sel);
const app = $("#app");
const toastEl = $("#toast");

const state = {
  token: sessionStorage.getItem("adminToken") || "",
  view: "dash",
  name: "",
  templates: [],
  wizard: null,
};

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[c]));
}

function toast(msg, err) {
  toastEl.hidden = false;
  toastEl.className = "toast" + (err ? " err" : "");
  toastEl.textContent = msg;
  clearTimeout(toastEl._t);
  toastEl._t = setTimeout(() => { toastEl.hidden = true; }, 3200);
}

async function api(path, opts = {}) {
  const headers = { "Content-Type": "application/json", ...(opts.headers || {}) };
  if (state.token) headers["X-Admin-Token"] = state.token;
  const res = await fetch("/api/admin" + path, { ...opts, headers });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (res.status === 401) {
    if (path !== "/login") {
      state.token = "";
      sessionStorage.removeItem("adminToken");
      route();
    }
    throw new Error(data?.error || "未授权");
  }
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

function route() {
  const hash = location.hash.replace(/^#\/?/, "");
  const [view, name] = hash.split("/");
  state.view = view || "dash";
  state.name = decodeURIComponent(name || "");
  render();
}

function nav(href, label, view) {
  return `<a href="#/${href}" class="${state.view === view ? "active" : ""}">${label}</a>`;
}

function badge(status) {
  const label = {
    connected: "已连接", error: "失败", connecting: "连接中",
    disabled: "已停用", disconnected: "未连接",
  }[status] || status;
  return `<span class="badge ${esc(status)}">${esc(label)}</span>`;
}

function shell(title, sub, actions, body) {
  return `
    <div class="shell">
      <aside class="side">
        <div class="mark"><i>MG</i> MCP Gateway</div>
        <nav class="nav">
          ${nav("", "总览", "dash")}
          ${nav("servers", "上游服务", "servers")}
          ${nav("deploy", "部署", "deploy")}
          ${nav("policies", "限制策略", "policies")}
          ${nav("keys", "API 密钥", "keys")}
          ${nav("activity", "调用记录", "activity")}
        </nav>
        <div class="side-foot">
          控制台口令仅保存在本机会话
          <button class="btn ghost sm" id="logout">退出</button>
        </div>
      </aside>
      <main class="main">
        <div class="top">
          <div>
            <h2>${title}</h2>
            <div class="sub">${sub}</div>
          </div>
          <div class="row">${actions || ""}</div>
        </div>
        ${body}
      </main>
    </div>`;
}

function renderLogin() {
  app.innerHTML = `
    <div class="login">
      <form class="login-card" id="login">
        <div class="mark"><i>MG</i> MCP Gateway</div>
        <h1>控制台</h1>
        <p>部署上游 MCP Server，并按服务 / 密钥限制工具与调用频率。</p>
        <label class="field">
          <span>管理员口令</span>
          <input name="token" type="password" autocomplete="current-password" placeholder="GATEWAY_ADMIN_TOKEN" required />
        </label>
        <div style="height:14px"></div>
        <button class="btn primary" type="submit" style="width:100%">进入</button>
        <div class="hint">默认口令是 application.yml 里的 change-me，建议用环境变量覆盖。</div>
      </form>
    </div>`;
  $("#login").onsubmit = async (e) => {
    e.preventDefault();
    const token = new FormData(e.target).get("token");
    try {
      await api("/login", { method: "POST", body: JSON.stringify({ token }) });
      state.token = token;
      sessionStorage.setItem("adminToken", token);
      location.hash = "#/";
      route();
    } catch (err) { toast(err.message, true); }
  };
}

async function renderDash() {
  const o = await api("/overview");
  const s = o.servers;
  app.innerHTML = shell("总览", "一个入口，聚合并看管所有上游 MCP。", "", `
    <div class="grid stats">
      <div class="card"><h3>上游</h3><div class="stat">${s.connected}<small>/ ${s.total} 已连接</small></div></div>
      <div class="card"><h3>已发现工具</h3><div class="stat">${o.tools}</div></div>
      <div class="card"><h3>API 密钥</h3><div class="stat">${o.keys.enabled}<small>/ ${o.keys.total} 启用</small></div></div>
      <div class="card"><h3>近 1 小时调用</h3><div class="stat">${o.calls.lastHour}<small>拒绝 ${o.calls.deniedLastHour}</small></div></div>
    </div>
    <div class="grid split" style="margin-top:14px">
      <div class="card">
        <h3>上游健康</h3>
        ${o.health.length ? `<div class="table-wrap"><table>
          <thead><tr><th>名称</th><th>传输</th><th>状态</th><th>工具</th></tr></thead>
          <tbody>${o.health.map((h) => `<tr>
            <td><a href="#/servers/${encodeURIComponent(h.name)}">${esc(h.name)}</a></td>
            <td class="mono">${esc(h.transport)}</td>
            <td>${badge(h.status)}</td>
            <td>${h.toolCount}</td>
          </tr>`).join("")}</tbody>
        </table></div>` : `<div class="empty">还没有上游。去部署一个。</div>`}
      </div>
      <div class="card">
        <h3>接入 Cursor</h3>
        <p class="sub" style="margin:0 0 10px">把网关当作唯一 MCP，客户端不用直连各个 server。</p>
        <pre class="snippet">${esc(`{
  "mcpServers": {
    "my-gateway": {
      "url": "${o.endpoint}",
      "headers": { "Authorization": "Bearer <API Key>" }
    }
  }
}`)}</pre>
        <div style="height:16px"></div>
        <h3>最近调用</h3>
        ${o.recent.length ? o.recent.map((l) => `
          <div class="tool">
            <div>
              <b class="mono">${esc(l.tool)}</b>
              <p>${esc(l.keyName)} · ${esc(l.durationMs)}ms${l.error ? " · " + esc(l.error) : ""}</p>
            </div>
            ${l.denied ? badge("error") : l.ok ? badge("connected") : badge("error")}
          </div>`).join("") : `<div class="empty">暂无调用</div>`}
      </div>
    </div>`);
  bindChrome();
}

async function renderServers() {
  const list = await api("/servers");
  app.innerHTML = shell("上游服务", "连接中的进程或远程 HTTP 端点。",
    `<a class="btn primary" href="#/deploy">部署新服务</a>`, `
    <div class="card">
      ${list.length ? `<div class="table-wrap"><table>
        <thead><tr><th>名称</th><th>传输</th><th>状态</th><th>工具</th><th>限制</th><th></th></tr></thead>
        <tbody>${list.map((s) => {
          const p = s.policy || {};
          const limits = [
            p.allowedTools?.length ? `允许 ${p.allowedTools.length}` : "",
            p.deniedTools?.length ? `拒绝 ${p.deniedTools.length}` : "",
            p.maxCallsPerMinute ? `${p.maxCallsPerMinute}/分` : "",
          ].filter(Boolean).join(" · ") || "无";
          return `<tr>
            <td><a href="#/servers/${encodeURIComponent(s.name)}">${esc(s.name)}</a></td>
            <td class="mono">${esc(s.transport)}</td>
            <td>${badge(s.status)}${s.error ? `<div class="err-text">${esc(s.error)}</div>` : ""}</td>
            <td>${s.toolCount}</td>
            <td>${esc(limits)}</td>
            <td class="row">
              <button class="btn sm" data-act="connect" data-name="${esc(s.name)}">连接</button>
              <button class="btn sm" data-act="disconnect" data-name="${esc(s.name)}">断开</button>
            </td>
          </tr>`;
        }).join("")}</tbody>
      </table></div>` : `<div class="empty">还没有上游。<a href="#/deploy">部署第一个</a></div>`}
    </div>`);
  bindChrome();
  app.querySelectorAll("[data-act]").forEach((btn) => {
    btn.onclick = async () => {
      btn.disabled = true;
      try {
        await api(`/servers/${encodeURIComponent(btn.dataset.name)}/${btn.dataset.act}`, { method: "POST" });
        toast(btn.dataset.act === "connect" ? "已尝试连接" : "已断开");
        renderServers();
      } catch (e) { toast(e.message, true); btn.disabled = false; }
    };
  });
}

async function renderDetail() {
  const s = await api(`/servers/${encodeURIComponent(state.name)}`);
  const tools = s.tools || [];
  const allowed = new Set(s.policy?.allowedTools || []);
  const denied = new Set(s.policy?.deniedTools || []);
  app.innerHTML = shell(esc(s.name), `${s.transport} · ${s.command || s.url || ""}`,
    `<button class="btn" id="reconnect">重新连接</button>
     <button class="btn" id="disconnect">断开</button>
     <button class="btn danger" id="remove">删除</button>`, `
    <div class="grid split">
      <div class="card">
        <h3>连接</h3>
        <dl class="kv">
          <dt>状态</dt><dd>${badge(s.status)}</dd>
          <dt>上游信息</dt><dd>${esc(s.serverName || "—")} ${esc(s.serverVersion || "")}</dd>
          <dt>命令 / URL</dt><dd class="mono">${esc(s.command ? [s.command, ...(s.args || [])].join(" ") : (s.url || "") + (s.endpoint || ""))}</dd>
        </dl>
        ${s.error ? `<div class="err-text">${esc(s.error)}</div>` : ""}
        <div style="height:18px"></div>
        <h3>限制</h3>
        <p class="sub" style="margin:0 0 10px">允许列表为空表示该上游全部工具可用（仍受拒绝列表与密钥范围约束）。支持后缀 * 通配。</p>
        <form id="policy" class="grid" style="gap:12px">
          <label class="field"><span>允许的工具</span>
            <input name="allowed" value="${esc((s.policy?.allowedTools || []).join(", "))}" placeholder="read_file, list_*" /></label>
          <label class="field"><span>拒绝的工具</span>
            <input name="denied" value="${esc((s.policy?.deniedTools || []).join(", "))}" placeholder="write_file, delete_*" /></label>
          <label class="field"><span>每分钟最多调用</span>
            <input name="rpm" type="number" min="1" placeholder="不限制" value="${s.policy?.maxCallsPerMinute || ""}" /></label>
          <div><button class="btn primary" type="submit">保存策略</button></div>
        </form>
      </div>
      <div class="card">
        <h3>工具 ${tools.length ? `(${tools.length})` : ""}</h3>
        ${tools.length ? tools.map((t) => `
          <div class="tool">
            <div>
              <b class="mono">${esc(t.name)}</b>
              <p>${esc(t.description || t.title || "")}</p>
            </div>
            <div class="row">
              ${allowed.has(t.name) ? `<span class="badge connected">允许</span>` : ""}
              ${denied.has(t.name) ? `<span class="badge error">拒绝</span>` : ""}
            </div>
          </div>`).join("") : `<div class="empty">${s.status === "connected" ? "该上游没有工具" : "连接后才能拉取工具列表"}</div>`}
      </div>
    </div>`);
  bindChrome();
  $("#reconnect").onclick = () => mutateServer("connect", "正在连接…");
  $("#disconnect").onclick = () => mutateServer("disconnect", "已断开");
  $("#remove").onclick = async () => {
    if (!confirm(`删除上游 ${s.name}？进程会被停掉。`)) return;
    try {
      await api(`/servers/${encodeURIComponent(s.name)}`, { method: "DELETE" });
      location.hash = "#/servers";
    } catch (e) { toast(e.message, true); }
  };
  $("#policy").onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const split = (v) => String(v || "").split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
    try {
      const rpm = fd.get("rpm");
      await api(`/servers/${encodeURIComponent(s.name)}/policy`, {
        method: "PUT",
        body: JSON.stringify({
          allowedTools: split(fd.get("allowed")),
          deniedTools: split(fd.get("denied")),
          maxCallsPerMinute: rpm ? Number(rpm) : null,
        }),
      });
      toast("策略已保存");
      renderDetail();
    } catch (err) { toast(err.message, true); }
  };

  async function mutateServer(act, msg) {
    try {
      await api(`/servers/${encodeURIComponent(s.name)}/${act}`, { method: "POST" });
      toast(msg);
      renderDetail();
    } catch (e) { toast(e.message, true); }
  }
}

async function renderDeploy() {
  if (!state.templates.length) state.templates = await api("/templates");
  if (!state.wizard) state.wizard = { step: 1, tpl: null, fields: {}, name: "" };
  const w = state.wizard;
  const body = w.step === 1 ? `
    <div class="grid cards">
      ${state.templates.map((t) => `
        <button class="tpl ${w.tpl?.id === t.id ? "active" : ""}" data-id="${esc(t.id)}">
          <b>${esc(t.title)}</b>
          <small>${esc(t.description)}</small>
        </button>`).join("")}
    </div>` : `
    <form id="deploy-form" class="card grid" style="max-width:640px;gap:14px">
      <label class="field"><span>服务名（工具前缀）</span>
        <input name="name" required value="${esc(w.name)}" placeholder="filesystem" /></label>
      ${(w.tpl.fields || []).map((f) => `
        <label class="field"><span>${esc(f.label)}</span>
          <input name="${esc(f.key)}" ${f.secret ? "type=password" : ""} placeholder="${esc(f.placeholder)}"
            value="${esc(w.fields[f.key] || "")}" /></label>`).join("")}
      <label class="field"><span>部署后立即限制（可选，逗号分隔）</span>
        <input name="denied" placeholder="write_file, delete_file" /></label>
      <label class="field"><span>每分钟最多调用</span>
        <input name="rpm" type="number" min="1" placeholder="不限制" /></label>
      <div class="row">
        <button class="btn" type="button" id="back">返回</button>
        <button class="btn primary" type="submit">部署并连接</button>
      </div>
    </form>`;

  app.innerHTML = shell("部署 MCP Server", "选一个模板，填参数，网关会拉起或连上它。",
    w.step === 2 ? `<button class="btn ghost" id="back-top">重选模板</button>` : "", body);
  bindChrome();
  app.querySelectorAll(".tpl").forEach((el) => {
    el.onclick = () => {
      const tpl = state.templates.find((t) => t.id === el.dataset.id);
      state.wizard = { step: 2, tpl, fields: {}, name: tpl.id.startsWith("custom") ? "" : tpl.id };
      renderDeploy();
    };
  });
  const back = $("#back") || $("#back-top");
  if (back) back.onclick = () => { state.wizard.step = 1; renderDeploy(); };
  const form = $("#deploy-form");
  if (form) form.onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(form);
    const fields = {};
    for (const [k, v] of fd.entries()) fields[k] = String(v);
    const spec = buildSpec(w.tpl, fields);
    spec.policy = {
      allowedTools: [],
      deniedTools: String(fields.denied || "").split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean),
      maxCallsPerMinute: fields.rpm ? Number(fields.rpm) : null,
    };
    spec.connect = true;
    spec.enabled = true;
    const btn = form.querySelector("[type=submit]");
    btn.disabled = true;
    btn.textContent = "正在连接…";
    try {
      await api("/servers", { method: "POST", body: JSON.stringify(spec) });
      toast("已部署 " + spec.name);
      state.wizard = null;
      location.hash = "#/servers/" + encodeURIComponent(spec.name);
    } catch (err) {
      toast(err.message, true);
      btn.disabled = false;
      btn.textContent = "部署并连接";
    }
  };
}

function buildSpec(tpl, fields) {
  const fill = (s) => String(s || "").replace(/\{\{(\w+)\}\}/g, (_, k) => fields[k] || "");
  const spec = {
    name: fields.name.trim(),
    transport: tpl.transport,
    command: fill(tpl.command) || undefined,
    args: (tpl.args || []).map(fill).filter((a, i, arr) => a || i < arr.length - 1),
    env: {},
    headers: {},
    url: fields.url || undefined,
    endpoint: fields.endpoint || undefined,
  };
  if (fields.command) spec.command = fields.command.trim();
  if (fields.args) spec.args = fields.args.trim().split(/\s+/).filter(Boolean);
  if (fields.token) spec.headers.Authorization = "Bearer " + fields.token.trim();
  spec.args = spec.args.filter(Boolean);
  return spec;
}

async function renderPolicies() {
  const [servers, keys] = await Promise.all([api("/servers"), api("/keys")]);
  app.innerHTML = shell("限制策略", "两层：上游自己的允许/拒绝/限流，再加上每把密钥的可见范围。", "", `
    <div class="grid split">
      <div class="card">
        <h3>按上游</h3>
        ${servers.map((s) => {
          const p = s.policy || {};
          return `<div class="tool">
            <div>
              <b><a href="#/servers/${encodeURIComponent(s.name)}">${esc(s.name)}</a></b>
              <p>允许 ${p.allowedTools?.length || 0} · 拒绝 ${p.deniedTools?.length || 0} ·
                ${p.maxCallsPerMinute ? p.maxCallsPerMinute + "/分" : "不限流"}</p>
            </div>
            ${badge(s.status)}
          </div>`;
        }).join("") || `<div class="empty">没有上游</div>`}
      </div>
      <div class="card">
        <h3>按密钥</h3>
        ${keys.map((k) => `<div class="tool">
          <div>
            <b>${esc(k.name)}</b>
            <p>${k.allowedServers?.length ? "服务 " + k.allowedServers.join(", ") : "全部服务"} ·
              ${k.allowedTools?.length ? "工具 " + k.allowedTools.join(", ") : "全部工具"} ·
              ${k.maxCallsPerMinute ? k.maxCallsPerMinute + "/分" : "不限流"}</p>
          </div>
          ${k.enabled ? badge("connected") : badge("disabled")}
        </div>`).join("") || `<div class="empty">还没有密钥</div>`}
      </div>
    </div>`);
  bindChrome();
}

async function renderKeys() {
  const keys = await api("/keys");
  app.innerHTML = shell("API 密钥", "客户端调 /mcp 时用的 Bearer / X-API-Key。可限定能看见哪些上游和工具。",
    `<button class="btn primary" id="new-key">新建密钥</button>`, `
    <div class="card">
      ${keys.length ? `<div class="table-wrap"><table>
        <thead><tr><th>名称</th><th>密钥</th><th>范围</th><th>限流</th><th></th></tr></thead>
        <tbody>${keys.map((k) => `<tr>
          <td>${esc(k.name)} ${k.enabled ? "" : badge("disabled")}</td>
          <td class="mono">${esc(k.secret)}</td>
          <td>${esc([
            k.allowedServers?.length ? "服务: " + k.allowedServers.join(", ") : "全部服务",
            k.allowedTools?.length ? "工具: " + k.allowedTools.join(", ") : "全部工具",
          ].join(" · "))}</td>
          <td>${k.maxCallsPerMinute || "—"}</td>
          <td class="row">
            <button class="btn sm" data-edit="${esc(k.id)}">编辑</button>
            <button class="btn sm danger" data-del="${esc(k.id)}">删除</button>
          </td>
        </tr>`).join("")}</tbody>
      </table></div>` : `<div class="empty">没有密钥时，/mcp 不鉴权。建议至少留一把。</div>`}
    </div>
    <div id="key-modal"></div>`);
  bindChrome();
  $("#new-key").onclick = () => openKeyModal();
  app.querySelectorAll("[data-edit]").forEach((b) => {
    b.onclick = () => openKeyModal(keys.find((k) => k.id === b.dataset.edit));
  });
  app.querySelectorAll("[data-del]").forEach((b) => {
    b.onclick = async () => {
      if (!confirm("删除这把密钥？已分发出去的客户端会立刻失效。")) return;
      try {
        await api("/keys/" + b.dataset.del, { method: "DELETE" });
        renderKeys();
      } catch (e) { toast(e.message, true); }
    };
  });
}

function openKeyModal(existing) {
  const host = $("#key-modal");
  host.innerHTML = `
    <div class="modal-bg">
      <form class="modal" id="key-form">
        <h3 style="margin:0 0 14px">${existing ? "编辑密钥" : "新建密钥"}</h3>
        <div class="grid" style="gap:12px">
          <label class="field"><span>名称</span>
            <input name="name" required value="${esc(existing?.name || "")}" placeholder="cursor-local" /></label>
          <label class="field"><span>允许的上游（空=全部，逗号分隔）</span>
            <input name="servers" value="${esc((existing?.allowedServers || []).join(", "))}" placeholder="filesystem, memory" /></label>
          <label class="field"><span>允许的工具（空=全部，可写 server__tool 或 tool）</span>
            <input name="tools" value="${esc((existing?.allowedTools || []).join(", "))}" placeholder="read_file, filesystem__*" /></label>
          <label class="field"><span>每分钟最多调用</span>
            <input name="rpm" type="number" min="1" value="${existing?.maxCallsPerMinute || ""}" /></label>
          ${existing ? `<label class="row" style="color:var(--muted)">
            <input type="checkbox" name="enabled" ${existing.enabled ? "checked" : ""} /> 启用</label>` : ""}
        </div>
        <div class="row" style="margin-top:18px;justify-content:flex-end">
          <button class="btn ghost" type="button" id="cancel">取消</button>
          <button class="btn primary" type="submit">保存</button>
        </div>
      </form>
    </div>`;
  $("#cancel").onclick = () => { host.innerHTML = ""; };
  $(".modal-bg").onclick = (e) => { if (e.target.classList.contains("modal-bg")) host.innerHTML = ""; };
  $("#key-form").onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const split = (v) => String(v || "").split(/[,，\s]+/).map((x) => x.trim()).filter(Boolean);
    const body = {
      name: fd.get("name"),
      allowedServers: split(fd.get("servers")),
      allowedTools: split(fd.get("tools")),
      maxCallsPerMinute: fd.get("rpm") ? Number(fd.get("rpm")) : null,
      enabled: existing ? fd.get("enabled") === "on" : true,
    };
    try {
      const res = existing
        ? await api("/keys/" + existing.id, { method: "PUT", body: JSON.stringify(body) })
        : await api("/keys", { method: "POST", body: JSON.stringify(body) });
      host.innerHTML = "";
      if (res.once && res.secret) {
        openSecretOnce(res);
      } else {
        toast("已保存");
        renderKeys();
      }
    } catch (err) { toast(err.message, true); }
  };
}

function openSecretOnce(res) {
  const host = $("#key-modal");
  host.innerHTML = `
    <div class="modal-bg">
      <div class="modal">
        <h3 style="margin:0 0 10px">请立刻复制密钥</h3>
        <p class="sub">只显示这一次，关闭后控制台只留掩码。</p>
        <pre class="snippet" id="secret">${esc(res.secret)}</pre>
        <div class="row" style="margin-top:14px;justify-content:flex-end">
          <button class="btn" id="copy">复制</button>
          <button class="btn primary" id="done">已保存</button>
        </div>
      </div>
    </div>`;
  $("#copy").onclick = async () => {
    try { await navigator.clipboard.writeText(res.secret); toast("已复制"); }
    catch { toast("复制失败，请手动选中", true); }
  };
  $("#done").onclick = () => { host.innerHTML = ""; renderKeys(); };
}

async function renderActivity() {
  const logs = await api("/logs");
  app.innerHTML = shell("调用记录", "进程内最近 300 条 tools/call，重启后清空。", "", `
    <div class="card">
      ${logs.length ? `<div class="table-wrap"><table>
        <thead><tr><th>时间</th><th>工具</th><th>密钥</th><th>耗时</th><th>结果</th></tr></thead>
        <tbody>${logs.map((l) => `<tr>
          <td class="mono">${esc(String(l.at).replace("T", " ").slice(0, 19))}</td>
          <td class="mono">${esc(l.tool)}</td>
          <td>${esc(l.keyName)}</td>
          <td>${l.durationMs}ms</td>
          <td>${l.denied ? "策略拒绝" : l.ok ? "成功" : "失败"}${l.error ? `<div class="err-text">${esc(l.error)}</div>` : ""}</td>
        </tr>`).join("")}</tbody>
      </table></div>` : `<div class="empty">还没有调用</div>`}
    </div>`);
  bindChrome();
}

function bindChrome() {
  const logout = $("#logout");
  if (logout) logout.onclick = () => {
    state.token = "";
    sessionStorage.removeItem("adminToken");
    route();
  };
}

async function render() {
  if (!state.token) { renderLogin(); return; }
  try {
    if (state.view === "servers" && state.name) await renderDetail();
    else if (state.view === "servers") await renderServers();
    else if (state.view === "deploy") await renderDeploy();
    else if (state.view === "policies") await renderPolicies();
    else if (state.view === "keys") await renderKeys();
    else if (state.view === "activity") await renderActivity();
    else await renderDash();
  } catch (e) {
    app.innerHTML = shell("出错了", "", "", `<div class="card err-text">${esc(e.message)}</div>`);
    bindChrome();
  }
}

window.addEventListener("hashchange", route);
route();

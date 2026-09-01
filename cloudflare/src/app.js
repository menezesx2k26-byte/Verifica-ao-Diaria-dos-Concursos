import monitor from "./index.js";

const JSON_HEADERS = {"content-type": "application/json; charset=utf-8", "cache-control": "no-store"};

function json(value, init = {}) {
  return new Response(JSON.stringify(value), {status: init.status || 200, headers: {...JSON_HEADERS, ...(init.headers || {})}});
}

async function authOk(request, env) {
  if (!env.WATCHDOG_TOKEN) return false;
  const supplied = request.headers.get("authorization") || "";
  const expected = `Bearer ${env.WATCHDOG_TOKEN}`;
  const a = new TextEncoder().encode(supplied);
  const b = new TextEncoder().encode(expected);
  const [ha, hb] = await Promise.all([crypto.subtle.digest("SHA-256", a), crypto.subtle.digest("SHA-256", b)]);
  const aa = new Uint8Array(ha), bb = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
  return diff === 0;
}

function contestRow(x) {
  return {
    id: String(x.id || x.url || ""),
    organization: String(x.organization || x.source || ""),
    notice_number: String(x.notice_number || ""),
    year: Number.isFinite(Number(x.year)) ? Number(x.year) : null,
    title: String(x.title || "Oportunidade detectada"),
    city: String(x.city || ""),
    uf: String(x.uf || ""),
    region: String(x.region || ""),
    scope: String(x.scope || ""),
    board: String(x.board || ""),
    type: String(x.type || ""),
    education: String(x.education || ""),
    area: String(x.area || ""),
    remuneration: String(x.remuneration || ""),
    vacancies: String(x.vacancies || ""),
    fee: String(x.fee || ""),
    registration_start: String(x.start_date || x.registration_start || ""),
    registration_end: String(x.end_date || x.registration_end || ""),
    status: String(x.status || "detected"),
    source: String(x.source || ""),
    source_url: String(x.url || x.source_url || ""),
    edital_url: String(x.edital_url || x.url || ""),
    priority: Number(x.priority || 50),
    active: x.active === false ? 0 : 1,
    first_seen: String(x.first_seen || new Date().toISOString()),
    last_seen: String(x.last_seen || new Date().toISOString()),
    updated_at: new Date().toISOString(),
  };
}

function healthRow(x) {
  const legacy = Boolean(x.ok);
  return {
    id: String(x.id || ""), label: String(x.label || ""), url: String(x.url || ""),
    http_ok: x.http_ok == null ? (legacy ? 1 : 0) : (x.http_ok ? 1 : 0),
    parser_ok: x.parser_ok == null ? (legacy ? 1 : 0) : (x.parser_ok ? 1 : 0),
    semantic_ok: x.semantic_ok == null ? (legacy ? 1 : 0) : (x.semantic_ok ? 1 : 0),
    item_count: Number(x.item_count || 0), expected_min: Number(x.expected_min || 0),
    checked_at: String(x.checked_at || new Date().toISOString()),
    last_success_at: String(x.last_success_at || (x.ok ? x.checked_at : "") || ""),
    fingerprint: String(x.fingerprint || ""), scan_status: String(x.scan_status || (legacy ? "NO_CHANGE_CONFIRMED" : "UNKNOWN")),
    error: String(x.error || ""),
  };
}

async function ingest(request, env) {
  if (!(await authOk(request, env))) return new Response("unauthorized", {status: 401});
  if (!env.DB) return json({ok: false, error: "D1 binding DB ausente"}, {status: 503});
  const body = await request.json().catch(() => ({}));
  const contests = Array.isArray(body.contests || body.items) ? (body.contests || body.items).map(contestRow).filter(x => x.id) : [];
  const sources = Array.isArray(body.source_health || body.sources) ? (body.source_health || body.sources).map(healthRow).filter(x => x.id) : [];
  const documents = Array.isArray(body.documents) ? body.documents : [];
  const events = Array.isArray(body.events) ? body.events : [];
  const alerts = Array.isArray(body.alerts) ? body.alerts : [];
  const now = new Date().toISOString();
  const statements = [];

  for (const x of contests.slice(0, 2000)) {
    statements.push(env.DB.prepare(`INSERT INTO contests(id,organization,notice_number,year,title,city,uf,region,scope,board,type,education,area,remuneration,vacancies,fee,registration_start,registration_end,status,source,source_url,edital_url,priority,active,first_seen,last_seen,updated_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET organization=excluded.organization,notice_number=excluded.notice_number,year=excluded.year,title=excluded.title,city=excluded.city,uf=excluded.uf,region=excluded.region,scope=excluded.scope,board=excluded.board,type=excluded.type,education=excluded.education,area=excluded.area,remuneration=excluded.remuneration,vacancies=excluded.vacancies,fee=excluded.fee,registration_start=excluded.registration_start,registration_end=excluded.registration_end,status=excluded.status,source=excluded.source,source_url=excluded.source_url,edital_url=excluded.edital_url,priority=excluded.priority,active=excluded.active,last_seen=excluded.last_seen,updated_at=excluded.updated_at`)
      .bind(x.id,x.organization,x.notice_number,x.year,x.title,x.city,x.uf,x.region,x.scope,x.board,x.type,x.education,x.area,x.remuneration,x.vacancies,x.fee,x.registration_start,x.registration_end,x.status,x.source,x.source_url,x.edital_url,x.priority,x.active,x.first_seen,x.last_seen,x.updated_at));
  }

  const activeIds = new Set(contests.map(x => x.id));
  if (body.full_snapshot === true) {
    statements.push(env.DB.prepare("UPDATE contests SET active=0, updated_at=?").bind(now));
    for (const id of activeIds) statements.push(env.DB.prepare("UPDATE contests SET active=1, updated_at=? WHERE id=?").bind(now, id));
  }

  for (const x of sources.slice(0, 500)) {
    statements.push(env.DB.prepare(`INSERT INTO source_health(id,label,url,http_ok,parser_ok,semantic_ok,item_count,expected_min,checked_at,last_success_at,fingerprint,scan_status,error)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=excluded.label,url=excluded.url,http_ok=excluded.http_ok,parser_ok=excluded.parser_ok,semantic_ok=excluded.semantic_ok,item_count=excluded.item_count,expected_min=excluded.expected_min,checked_at=excluded.checked_at,last_success_at=excluded.last_success_at,fingerprint=excluded.fingerprint,scan_status=excluded.scan_status,error=excluded.error`)
      .bind(x.id,x.label,x.url,x.http_ok,x.parser_ok,x.semantic_ok,x.item_count,x.expected_min,x.checked_at,x.last_success_at,x.fingerprint,x.scan_status,x.error));
  }

  for (const d of documents.slice(0, 2000)) {
    if (!d?.id || !d?.url || !d?.sha256) continue;
    statements.push(env.DB.prepare(`INSERT INTO documents(id,contest_id,source_id,kind,title,url,sha256,published_at,fetched_at,text_excerpt,metadata_json)
      VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET contest_id=excluded.contest_id,kind=excluded.kind,title=excluded.title,url=excluded.url,sha256=excluded.sha256,published_at=excluded.published_at,fetched_at=excluded.fetched_at,text_excerpt=excluded.text_excerpt,metadata_json=excluded.metadata_json`)
      .bind(String(d.id), d.contest_id || null, String(d.source_id || ""), String(d.kind || "document"), String(d.title || ""), String(d.url), String(d.sha256), String(d.published_at || ""), String(d.fetched_at || now), String(d.text_excerpt || "").slice(0,12000), JSON.stringify(d.metadata || d.metadata_json || {})));
  }

  for (const e of events.slice(0, 2000)) {
    if (!e?.id || !e?.fingerprint) continue;
    statements.push(env.DB.prepare(`INSERT INTO events(id,contest_id,source_id,type,title,body,url,priority,happened_at,created_at,fingerprint)
      VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET contest_id=excluded.contest_id,type=excluded.type,title=excluded.title,body=excluded.body,url=excluded.url,priority=excluded.priority,happened_at=excluded.happened_at,created_at=excluded.created_at,fingerprint=excluded.fingerprint`)
      .bind(String(e.id), e.contest_id || null, String(e.source_id || ""), String(e.type || "change"), String(e.title || ""), String(e.body || ""), String(e.url || ""), Number(e.priority || 0), String(e.happened_at || now), String(e.created_at || now), String(e.fingerprint)));
  }

  for (const a of alerts.slice(0, 500)) {
    statements.push(env.DB.prepare("INSERT INTO alerts(event_id,title,body,url,priority,created_at) VALUES(?,?,?,?,?,?)")
      .bind(a.event_id || null, String(a.title || "Alerta"), String(a.body || ""), String(a.url || ""), Number(a.priority || 0), String(a.created_at || now)));
  }

  for (let i = 0; i < statements.length; i += 90) await env.DB.batch(statements.slice(i, i + 90));
  await env.DB.prepare("INSERT INTO meta(key,value) VALUES('last_ingest',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").bind(now).run();
  return json({ok: true, contests: contests.length, sources: sources.length, documents: documents.length, events: events.length, alerts: alerts.length, at: now});
}

async function listContests(url, env) {
  if (!env.DB) return json({items: [], source_health: [], source_count: 0, updated_at: null, degraded: true});
  const where = ["1=1"];
  const binds = [];
  if (url.searchParams.get("all") !== "1") where.push("active=1");
  for (const [param, col] of [["status","status"],["uf","uf"],["scope","scope"],["area","area"]]) {
    const v = url.searchParams.get(param); if (v) { where.push(`${col}=?`); binds.push(v); }
  }
  const q = url.searchParams.get("q");
  if (q) { where.push("(title LIKE ? OR organization LIKE ? OR city LIKE ? OR area LIKE ?)"); const like=`%${q}%`; binds.push(like,like,like,like); }
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 250), 1), 500);
  const rows = await env.DB.prepare(`SELECT id,title,organization,city,uf,region,scope,type,education,area,remuneration,vacancies,fee,registration_start AS start_date,registration_end AS end_date,status,source,source_url AS url,edital_url,first_seen,last_seen,priority,active FROM contests WHERE ${where.join(" AND ")} ORDER BY priority DESC, CASE status WHEN 'closing_soon' THEN 0 WHEN 'open' THEN 1 ELSE 2 END, updated_at DESC LIMIT ?`).bind(...binds,limit).all();
  const health = await env.DB.prepare("SELECT id,label,http_ok,parser_ok,semantic_ok,item_count,expected_min,checked_at,last_success_at,fingerprint,scan_status,error FROM source_health ORDER BY semantic_ok ASC,label ASC").all();
  const last = await env.DB.prepare("SELECT value FROM meta WHERE key='last_ingest'").first();
  return json({schema_version: 3, updated_at: last?.value || null, source_count: health.results?.length || 0, items: rows.results || [], source_health: health.results || []});
}

async function listAlerts(env) {
  if (!env.DB) return json({items: []});
  const rows = await env.DB.prepare("SELECT id,title,body,url,priority,created_at FROM alerts ORDER BY created_at DESC,id DESC LIMIT 200").all();
  return json({items: rows.results || []});
}

async function listSources(env) {
  if (!env.DB) return json({items: []});
  const rows = await env.DB.prepare("SELECT * FROM source_health ORDER BY semantic_ok ASC,label ASC").all();
  return json({items: rows.results || []});
}

async function latestRelease() {
  const r = await fetch("https://api.github.com/repos/menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos/releases/latest", {headers:{"user-agent":"ConcursosWatch-Cloudflare/5.0","accept":"application/vnd.github+json"}});
  if (!r.ok) return json({version: ""}, {status: 502});
  const x = await r.json();
  return json({version: String(x.tag_name || "").replace(/^android-v/, ""), url: x.html_url || ""});
}

export default {
  scheduled(controller, env, ctx) { return monitor.scheduled(controller, env, ctx); },
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/contests" && request.method === "GET") return listContests(url, env);
    if (url.pathname === "/api/v1/alerts" && request.method === "GET") return listAlerts(env);
    if (url.pathname === "/api/v1/sources" && request.method === "GET") return listSources(env);
    if (url.pathname === "/api/v1/releases/latest" && request.method === "GET") return latestRelease();
    if (url.pathname === "/api/v1/ingest" && request.method === "POST") return ingest(request, env);
    return monitor.fetch(request, env, ctx);
  }
};

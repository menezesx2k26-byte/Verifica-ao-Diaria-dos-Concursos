const SOURCES = [
  ["sv_pref", "PMSV — Concurso 02/2026", "https://www.saovicente.sp.gov.br/institucional/concursos/concurso-no-02-2026", ["assistente-tecnico de gestao", "classificacao", "resultado", "homolog", "convoca", "nomea", "posse"]],
  ["sv_ibam", "IBAM — São Vicente 02/2026", "https://www.ibamsp-concursos.org.br/informacoes/134/", ["assistente-tecnico de gestao", "classificacao", "resultado", "homolog", "convoca", "nomea"]],
  ["sv_conv", "PMSV — Convocações 2026", "https://www.saovicente.sp.gov.br/institucional/concursos/convocacoes/convocacao-concursos-de-2026", ["assistente-tecnico de gestao", "02/2026", "convoca", "nomea", "posse"]],
  ["sv_bom", "PMSV — Boletim Oficial", "https://www.saovicente.sp.gov.br/transparencia/bom?limite=30", ["boletim oficial", "assistente-tecnico de gestao", "02/2026"]],
  ["pg_conc", "PG — Concurso 004/2024 ACS", "https://www.praiagrande.sp.gov.br/administracao/concurso_publico.asp?cd_pagina=187", ["004/2024", "agente comunitario de saude", "convoca", "nomea", "posse", "tornando sem efeito"]],
  ["pg_diario", "PG — Diário Oficial", "https://plenussistemas.dioenet.com.br/list/praia-grande", ["diario oficial", "004/2024", "agente comunitario de saude"]]
];

const enc = new TextEncoder();

function fold(s) {
  return (s || "").normalize("NFKD").replace(/\p{Diacritic}/gu, "").toLowerCase()
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ").replace(/&amp;/gi, "&")
    .replace(/\s+/g, " ").trim();
}

async function hash(s) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return [...new Uint8Array(d)].map(x => x.toString(16).padStart(2, "0")).join("");
}

function windows(html, terms, radius = 480) {
  const text = fold(html); const out = new Set();
  for (const raw of terms) {
    const term = fold(raw); let start = 0;
    while (term) {
      const i = text.indexOf(term, start); if (i < 0) break;
      out.add(text.slice(Math.max(0, i - radius), Math.min(text.length, i + term.length + radius)));
      start = i + Math.max(1, term.length);
    }
  }
  return [...out].sort();
}

async function sendTelegram(env, text) {
  if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_CHAT_ID) return;
  const r = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST", headers: {"content-type": "application/json"},
    body: JSON.stringify({chat_id: env.TELEGRAM_CHAT_ID, text: text.slice(0, 3900), disable_web_page_preview: true})
  });
  if (!r.ok) throw new Error(`Telegram HTTP ${r.status}`);
}

async function sendNtfy(env, text) {
  if (!env.NTFY_TOPIC) return;
  const base = (env.NTFY_SERVER || "https://ntfy.sh").replace(/\/$/, "");
  const headers = {"Title": "Concursos Watch — Cloudflare", "Priority": "high"};
  if (env.NTFY_TOKEN) headers.Authorization = `Bearer ${env.NTFY_TOKEN}`;
  const r = await fetch(`${base}/${env.NTFY_TOPIC}`, {method: "POST", headers, body: text});
  if (!r.ok) throw new Error(`ntfy HTTP ${r.status}`);
}

async function sendEmail(env, subject, text) {
  if (!env.EMAIL || !env.CF_EMAIL_FROM || !env.CF_EMAIL_TO) return;
  await env.EMAIL.send({
    from: env.CF_EMAIL_FROM,
    to: env.CF_EMAIL_TO.split(",").map(x => x.trim()).filter(Boolean),
    subject: subject.slice(0, 200),
    text
  });
}

async function notify(env, subject, text) {
  const results = await Promise.allSettled([
    sendTelegram(env, text), sendNtfy(env, text), sendEmail(env, subject, text)
  ]);
  for (const r of results) if (r.status === "rejected") console.error(JSON.stringify({event: "notify_error", error: String(r.reason)}));
}

async function authOk(request, env) {
  if (!env.WATCHDOG_TOKEN) return false;
  return request.headers.get("authorization") === `Bearer ${env.WATCHDOG_TOKEN}`;
}

async function run(env) {
  const alerts = [];
  const now = new Date().toISOString();

  await Promise.all(SOURCES.map(async ([id, label, url, terms]) => {
    const key = `source:${id}`;
    const old = await env.WATCH_STATE.get(key, "json") || {};
    try {
      const r = await fetch(url, {headers: {"user-agent": "ConcursosWatch-CF/2.0", "accept-language": "pt-BR,pt;q=0.9", "cache-control": "no-cache"}});
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const html = await r.text();
      const chunks = windows(html, terms);
      const sigs = [];
      for (const c of chunks) sigs.push(await hash(c));
      sigs.sort();
      if (Array.isArray(old.sigs)) {
        const prev = new Set(old.sigs);
        const added = sigs.filter(s => !prev.has(s));
        if (added.length) alerts.push([`[CF] NOVIDADE — ${label}`, `🚨 Cloudflare detectou mudança relevante.\n${url}`]);
      }
      if ((old.failures || 0) >= 3) alerts.push([`[CF] RECUPERADO — ${label}`, `✅ A fonte voltou a responder.\n${url}`]);
      await env.WATCH_STATE.put(key, JSON.stringify({sigs, failures: 0, last_ok: now, url, label}));
    } catch (err) {
      const failures = Math.min((old.failures || 0) + 1, 3);
      await env.WATCH_STATE.put(key, JSON.stringify({...old, failures, last_error: String(err), label, url}));
      if (failures === 3 && (old.failures || 0) < 3) alerts.push([`[CF] FONTE INDISPONÍVEL — ${label}`, `⚠️ Fonte falhou 3 vezes seguidas.\n${String(err)}\n${url}`]);
    }
  }));

  const hb = await env.WATCH_STATE.get("heartbeat:github", "json");
  const oldWd = await env.WATCH_STATE.get("watchdog:github", "json") || {problem: false};
  let problem = false;
  if (hb?.at) {
    const age = (Date.now() - Date.parse(hb.at)) / 60000;
    problem = age > 40;
    if (problem && !oldWd.problem) alerts.push(["[CF] WATCHDOG — GitHub Actions", `⚠️ GitHub está sem heartbeat há ${Math.round(age)} minutos.`]);
    if (!problem && oldWd.problem) alerts.push(["[CF] RECUPERADO — GitHub Actions", "✅ GitHub Actions voltou a enviar heartbeat."]);
  }
  await env.WATCH_STATE.put("watchdog:github", JSON.stringify({problem, checked_at: now}));
  await env.WATCH_STATE.put("heartbeat:cloudflare", JSON.stringify({at: now}));

  for (const [subject, text] of alerts.slice(0, 10)) await notify(env, subject, text);
  return {ok: true, checked: SOURCES.length, alerts: alerts.length, at: now};
}

export default {
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(run(env));
  },

  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      const cf = await env.WATCH_STATE.get("heartbeat:cloudflare", "json");
      const gh = await env.WATCH_STATE.get("heartbeat:github", "json");
      return Response.json({ok: true, last_cloudflare_run: cf?.at || null, last_github_heartbeat: gh?.at || null});
    }
    if (url.pathname === "/heartbeat/github" && request.method === "POST") {
      if (!(await authOk(request, env))) return new Response("unauthorized", {status: 401});
      const body = await request.json().catch(() => ({}));
      await env.WATCH_STATE.put("heartbeat:github", JSON.stringify({at: body.at || new Date().toISOString()}));
      return Response.json({ok: true});
    }
    if (url.pathname === "/run" && request.method === "POST") {
      if (!(await authOk(request, env))) return new Response("unauthorized", {status: 401});
      return Response.json(await run(env));
    }
    return new Response("concursos-watch-cloudflare", {status: 200});
  }
};

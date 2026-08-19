const SOURCES = [
  {
    id: "sv_pref",
    label: "PMSV — Concurso 02/2026",
    url: "https://www.saovicente.sp.gov.br/institucional/concursos/concurso-no-02-2026",
    radius: 950,
    triggers: ["classificacao", "resultado", "homolog", "convoca", "nomea", "posse", "reclassifica", "assistente-tecnico de gestao"],
    groups: []
  },
  {
    id: "sv_ibam",
    label: "IBAM — São Vicente 02/2026",
    url: "https://www.ibamsp-concursos.org.br/informacoes/134/",
    radius: 950,
    triggers: ["classificacao", "resultado", "homolog", "convoca", "nomea", "assistente-tecnico de gestao"],
    groups: []
  },
  {
    id: "sv_conv",
    label: "PMSV — Convocações 2026",
    url: "https://www.saovicente.sp.gov.br/institucional/concursos/convocacoes/convocacao-concursos-de-2026",
    radius: 1400,
    triggers: ["convoca", "nomea", "posse", "reclassifica"],
    groups: [["assistente-tecnico de gestao", "02/2026", "concurso 02/2026"]]
  },
  {
    id: "sv_bom",
    label: "PMSV — Boletim Oficial",
    url: "https://www.saovicente.sp.gov.br/transparencia/bom?limite=30",
    radius: 1400,
    triggers: ["assistente-tecnico de gestao", "02/2026", "concurso 02/2026", "convoca", "nomea", "homolog", "posse"],
    groups: [["assistente-tecnico de gestao", "02/2026", "concurso 02/2026"]]
  },
  {
    id: "pg_conc",
    label: "PG — Concurso 004/2024 ACS",
    url: "https://www.praiagrande.sp.gov.br/administracao/concurso_publico.asp?cd_pagina=187",
    radius: 1700,
    triggers: ["convoca", "nomea", "posse", "tornando sem efeito", "reclassifica", "homolog", "classificacao"],
    groups: [["004/2024", "004-2024", "004 2024"], ["agente comunitario de saude", " acs "]]
  },
  {
    id: "pg_diario",
    label: "PG — Diário Oficial",
    url: "https://plenussistemas.dioenet.com.br/list/praia-grande",
    radius: 1600,
    triggers: ["004/2024", "agente comunitario de saude", "convoca", "nomea", "posse", "portaria", "tornando sem efeito", "reclassifica"],
    groups: [
      ["004/2024", "004-2024", "004 2024", "agente comunitario de saude"],
      ["convoca", "nomea", "posse", "portaria", "tornando sem efeito", "reclassifica"]
    ]
  }
];

const enc = new TextEncoder();
const STALE_GITHUB_MINUTES = 40;
const MISSING_GITHUB_GRACE_MINUTES = 90;

function fold(s) {
  return (s || "")
    .normalize("NFKD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/\s+/g, " ")
    .trim();
}

async function sha256Bytes(s) {
  const d = await crypto.subtle.digest("SHA-256", enc.encode(s));
  return new Uint8Array(d);
}

async function hash(s) {
  const bytes = await sha256Bytes(s);
  return [...bytes].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function secureEqual(a, b) {
  const aa = await sha256Bytes(a || "");
  const bb = await sha256Bytes(b || "");
  let diff = 0;
  for (let i = 0; i < aa.length; i += 1) diff |= aa[i] ^ bb[i];
  return diff === 0;
}

function priorityTerms(env) {
  return (env.WATCH_PRIORITY_TERMS || "")
    .split(/[,;\n]+/)
    .map((x) => x.trim())
    .filter(Boolean);
}

function groupsMatch(text, groups) {
  if (!groups?.length) return true;
  const t = fold(text);
  return groups.every((group) => group.some((term) => t.includes(fold(term))));
}

function anyMatch(text, terms) {
  if (!terms?.length) return true;
  const t = fold(text);
  return terms.some((term) => t.includes(fold(term)));
}

function windows(text, terms, radius = 700) {
  const t = fold(text);
  const out = new Set();
  for (const raw of terms) {
    const term = fold(raw);
    let start = 0;
    while (term) {
      const i = t.indexOf(term, start);
      if (i < 0) break;
      out.add(t.slice(Math.max(0, i - radius), Math.min(t.length, i + term.length + radius)));
      start = i + Math.max(1, term.length);
    }
  }
  return [...out].sort();
}

function relevantChunks(source, html, env) {
  const pterms = priorityTerms(env);
  const terms = [...new Set([...source.triggers, ...pterms])];
  return windows(html, terms, source.radius).filter((chunk) => {
    if (pterms.length && anyMatch(chunk, pterms)) return true;
    return groupsMatch(chunk, source.groups) && anyMatch(chunk, source.triggers);
  });
}

async function sendTelegram(env, subject, text) {
  if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_CHAT_ID) return false;
  const body = `${subject}\n\n${text}`.slice(0, 3900);
  const r = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: {"content-type": "application/json"},
    body: JSON.stringify({
      chat_id: env.TELEGRAM_CHAT_ID,
      text: body,
      disable_web_page_preview: true
    })
  });
  if (!r.ok) throw new Error(`Telegram HTTP ${r.status}`);
  return true;
}

async function sendNtfy(env, subject, text) {
  if (!env.NTFY_TOPIC) return false;
  const base = (env.NTFY_SERVER || "https://ntfy.sh").replace(/\/$/, "");
  const headers = {"Title": subject.slice(0, 120), "Priority": "high"};
  if (env.NTFY_TOKEN) headers.Authorization = `Bearer ${env.NTFY_TOKEN}`;
  const r = await fetch(`${base}/${env.NTFY_TOPIC}`, {method: "POST", headers, body: text});
  if (!r.ok) throw new Error(`ntfy HTTP ${r.status}`);
  return true;
}

async function sendEmail(env, subject, text) {
  if (!env.EMAIL || !env.CF_EMAIL_FROM || !env.CF_EMAIL_TO) return false;
  const recipients = env.CF_EMAIL_TO.split(",").map((x) => x.trim()).filter(Boolean);
  if (!recipients.length) return false;
  await env.EMAIL.send({
    from: env.CF_EMAIL_FROM,
    to: recipients,
    subject: subject.slice(0, 200),
    text
  });
  return true;
}

async function notify(env, subject, text) {
  const results = await Promise.allSettled([
    sendTelegram(env, subject, text),
    sendNtfy(env, subject, text),
    sendEmail(env, subject, text)
  ]);
  const delivered = [];
  const failed = [];
  for (let i = 0; i < results.length; i += 1) {
    const name = ["telegram", "ntfy", "email"][i];
    const result = results[i];
    if (result.status === "fulfilled" && result.value) delivered.push(name);
    if (result.status === "rejected") failed.push(`${name}: ${String(result.reason)}`);
  }
  console.log(JSON.stringify({event: "notify", subject, delivered, failed}));
  return delivered;
}

async function authOk(request, env) {
  if (!env.WATCHDOG_TOKEN) return false;
  const supplied = request.headers.get("authorization") || "";
  const expected = `Bearer ${env.WATCHDOG_TOKEN}`;
  return secureEqual(supplied, expected);
}

function localDateHour(timeZone = "America/Sao_Paulo") {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    hourCycle: "h23"
  }).formatToParts(new Date());
  const get = (type) => parts.find((p) => p.type === type)?.value || "";
  return {date: `${get("year")}-${get("month")}-${get("day")}`, hour: Number(get("hour"))};
}

async function maybeDailyHealth(env, sourceResults) {
  const {date, hour} = localDateHour();
  if (hour < 9) return;
  const key = `daily-health:${date}`;
  if (await env.WATCH_STATE.get(key)) return;

  const healthy = sourceResults.filter((x) => x.ok).length;
  const failed = sourceResults.length - healthy;
  const text =
    `💓 Heartbeat diário do monitor Cloudflare.\n\n` +
    `Fontes acessíveis: ${healthy}/${sourceResults.length}.\n` +
    `Fontes com falha nesta rodada: ${failed}.\n` +
    `GitHub é monitorado por heartbeat independente.`;

  const delivered = await notify(env, `[CF][HEALTH-${date}] SAÚDE DIÁRIA`, text);
  if (delivered.length) {
    await env.WATCH_STATE.put(key, new Date().toISOString(), {expirationTtl: 60 * 60 * 48});
  }
}

async function githubWatchdog(env, alerts, now) {
  const hb = await env.WATCH_STATE.get("heartbeat:github", "json");
  const oldWd = await env.WATCH_STATE.get("watchdog:github", "json") || {problem: false};
  let problem = false;
  let reason = "";

  if (hb?.at) {
    const age = (Date.now() - Date.parse(hb.at)) / 60000;
    problem = !Number.isFinite(age) || age > STALE_GITHUB_MINUTES;
    if (problem) reason = `GitHub está sem heartbeat há ${Math.round(age)} minutos.`;
    await env.WATCH_STATE.delete("github:missing-since");
  } else {
    let missingSince = await env.WATCH_STATE.get("github:missing-since");
    if (!missingSince) {
      missingSince = now;
      await env.WATCH_STATE.put("github:missing-since", missingSince);
    }
    const age = (Date.now() - Date.parse(missingSince)) / 60000;
    problem = Number.isFinite(age) && age > MISSING_GITHUB_GRACE_MINUTES;
    if (problem) reason = `GitHub ainda não enviou heartbeat após ${Math.round(age)} minutos.`;
  }

  if (problem && !oldWd.problem) {
    alerts.push(["[CF] WATCHDOG — GitHub Actions", `⚠️ ${reason}`]);
  }
  if (!problem && oldWd.problem) {
    alerts.push(["[CF] RECUPERADO — GitHub Actions", "✅ GitHub Actions voltou a enviar heartbeat."]);
  }
  await env.WATCH_STATE.put("watchdog:github", JSON.stringify({problem, reason, checked_at: now}));
}

async function run(env) {
  const alerts = [];
  const now = new Date().toISOString();
  const sourceResults = [];

  await Promise.all(SOURCES.map(async (source) => {
    const key = `source:${source.id}`;
    const old = await env.WATCH_STATE.get(key, "json") || {};
    try {
      const r = await fetch(source.url, {
        headers: {
          "user-agent": "ConcursosWatch-CF/3.0",
          "accept-language": "pt-BR,pt;q=0.9",
          "cache-control": "no-cache"
        }
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const html = await r.text();
      const chunks = relevantChunks(source, html, env);
      const sigs = [];
      for (const chunk of chunks) sigs.push(await hash(chunk));
      sigs.sort();

      if (Array.isArray(old.sigs)) {
        const prev = new Set(old.sigs);
        const added = sigs.filter((sig) => !prev.has(sig));
        if (added.length) {
          const priority = priorityTerms(env).length && chunks.some((c) => anyMatch(c, priorityTerms(env)));
          alerts.push([
            priority ? `[CF] PRIORIDADE — ${source.label}` : `[CF] NOVIDADE — ${source.label}`,
            `🚨 Cloudflare detectou conteúdo relevante novo.\n${source.url}`
          ]);
        }
      }

      if ((old.failures || 0) >= 3) {
        alerts.push([`[CF] RECUPERADO — ${source.label}`, `✅ A fonte voltou a responder.\n${source.url}`]);
      }

      await env.WATCH_STATE.put(key, JSON.stringify({
        sigs,
        failures: 0,
        last_ok: now,
        url: source.url,
        label: source.label,
        chunks: chunks.length
      }));
      sourceResults.push({id: source.id, ok: true});
      console.log(JSON.stringify({event: "check_ok", source: source.id, chunks: chunks.length}));
    } catch (err) {
      const failures = Math.min((old.failures || 0) + 1, 3);
      await env.WATCH_STATE.put(key, JSON.stringify({
        ...old,
        failures,
        last_error: String(err),
        label: source.label,
        url: source.url
      }));
      if (failures === 3 && (old.failures || 0) < 3) {
        alerts.push([
          `[CF] FONTE INDISPONÍVEL — ${source.label}`,
          `⚠️ Fonte falhou 3 verificações seguidas.\n${String(err)}\n${source.url}`
        ]);
      }
      sourceResults.push({id: source.id, ok: false});
      console.error(JSON.stringify({event: "check_error", source: source.id, failures, error: String(err)}));
    }
  }));

  await githubWatchdog(env, alerts, now);
  await env.WATCH_STATE.put("heartbeat:cloudflare", JSON.stringify({at: now}));

  for (const [subject, text] of alerts.slice(0, 12)) {
    await notify(env, subject, text);
  }
  await maybeDailyHealth(env, sourceResults);

  return {
    ok: true,
    checked: SOURCES.length,
    source_failures: sourceResults.filter((x) => !x.ok).length,
    alerts: alerts.length,
    at: now
  };
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
      const wd = await env.WATCH_STATE.get("watchdog:github", "json");
      return Response.json({
        ok: true,
        service: "concursos-watch-cloudflare",
        last_cloudflare_run: cf?.at || null,
        last_github_heartbeat: gh?.at || null,
        github_watchdog: wd || null
      });
    }

    if (url.pathname === "/heartbeat/github" && request.method === "POST") {
      if (!(await authOk(request, env))) return new Response("unauthorized", {status: 401});
      const body = await request.json().catch(() => ({}));
      const at = typeof body.at === "string" ? body.at : new Date().toISOString();
      await env.WATCH_STATE.put("heartbeat:github", JSON.stringify({at, source: "github-actions"}));
      return Response.json({ok: true});
    }

    if (url.pathname === "/run" && request.method === "POST") {
      if (!(await authOk(request, env))) return new Response("unauthorized", {status: 401});
      return Response.json(await run(env));
    }

    if (url.pathname === "/test-alert" && request.method === "POST") {
      if (!(await authOk(request, env))) return new Response("unauthorized", {status: 401});
      const delivered = await notify(
        env,
        "[CF] TESTE OPERACIONAL",
        "🧪 Teste manual do monitor Cloudflare. Este aviso confirma os canais configurados no Worker."
      );
      return Response.json({ok: true, delivered});
    }

    return new Response("concursos-watch-cloudflare", {status: 200});
  }
};

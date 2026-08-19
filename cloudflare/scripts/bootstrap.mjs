import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";

const offline = process.argv.includes("--offline");
const accountId = process.env.CLOUDFLARE_ACCOUNT_ID || "";
const apiToken = process.env.CLOUDFLARE_API_TOKEN || "";
const namespaceTitle = process.env.CF_KV_NAMESPACE_TITLE || "concursos-watch-state";

function stripJsonComments(raw) {
  return raw
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");
}

async function cf(path, options = {}) {
  const response = await fetch(`https://api.cloudflare.com/client/v4${path}`, {
    ...options,
    headers: {
      authorization: `Bearer ${apiToken}`,
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    const details = JSON.stringify(data.errors || data);
    throw new Error(`Cloudflare API ${response.status}: ${details.slice(0, 1200)}`);
  }
  return data;
}

async function findNamespace() {
  for (let page = 1; page <= 20; page += 1) {
    const data = await cf(`/accounts/${accountId}/storage/kv/namespaces?per_page=100&page=${page}`);
    const rows = Array.isArray(data.result) ? data.result : [];
    const found = rows.find((x) => x.title === namespaceTitle);
    if (found) return found;
    if (rows.length < 100) break;
  }
  return null;
}

async function ensureNamespace() {
  if (offline) {
    return { id: "00000000000000000000000000000000", title: namespaceTitle };
  }
  if (!accountId || !apiToken) {
    throw new Error("CLOUDFLARE_ACCOUNT_ID e CLOUDFLARE_API_TOKEN são obrigatórios.");
  }
  const existing = await findNamespace();
  if (existing) {
    console.log(`[bootstrap] KV existente: ${existing.title}`);
    return existing;
  }
  const data = await cf(`/accounts/${accountId}/storage/kv/namespaces`, {
    method: "POST",
    body: JSON.stringify({ title: namespaceTitle }),
  });
  console.log(`[bootstrap] KV criado: ${data.result.title}`);
  return data.result;
}

const raw = await readFile(new URL("../wrangler.jsonc", import.meta.url), "utf8");
const config = JSON.parse(stripJsonComments(raw));
const ns = await ensureNamespace();

config.kv_namespaces = [{ binding: "WATCH_STATE", id: ns.id }];
config.secrets = { required: ["WATCHDOG_TOKEN"] };

const emailEnabled = /^(1|true|yes|sim)$/i.test(process.env.CF_EMAIL_ENABLED || "");
if (emailEnabled) {
  const from = (process.env.CF_EMAIL_FROM || "").trim();
  const to = (process.env.CF_EMAIL_TO || "")
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);
  if (!from || !to.length) {
    throw new Error("CF_EMAIL_ENABLED está ativo, mas CF_EMAIL_FROM/CF_EMAIL_TO não foram configurados.");
  }
  config.send_email = [
    {
      name: "EMAIL",
      allowed_sender_addresses: [from],
      allowed_destination_addresses: to,
    },
  ];
}

await writeFile(
  new URL("../wrangler.generated.jsonc", import.meta.url),
  `${JSON.stringify(config, null, 2)}\n`,
  "utf8",
);

console.log(`[bootstrap] configuração gerada com WATCH_STATE=${ns.id.slice(0, 6)}…`);

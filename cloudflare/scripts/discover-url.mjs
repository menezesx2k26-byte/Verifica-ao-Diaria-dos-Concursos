import { mkdir, writeFile } from "node:fs/promises";
import process from "node:process";

const accountId = process.env.CLOUDFLARE_ACCOUNT_ID || "";
const apiToken = process.env.CLOUDFLARE_API_TOKEN || "";
const workerName = process.env.CF_WORKER_NAME || "concursos-watch-redundante";

if (!accountId || !apiToken) {
  throw new Error("CLOUDFLARE_ACCOUNT_ID e CLOUDFLARE_API_TOKEN são obrigatórios.");
}

async function cf(path) {
  const r = await fetch(`https://api.cloudflare.com/client/v4${path}`, {
    headers: { authorization: `Bearer ${apiToken}` },
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok || data.success === false) {
    throw new Error(`Cloudflare API ${r.status}: ${JSON.stringify(data.errors || data).slice(0, 1200)}`);
  }
  return data.result;
}

const accountSubdomain = await cf(`/accounts/${accountId}/workers/subdomain`);
const scriptSubdomain = await cf(`/accounts/${accountId}/workers/scripts/${workerName}/subdomain`);
if (!scriptSubdomain?.enabled) {
  throw new Error(`workers.dev não está habilitado para ${workerName}.`);
}

const baseUrl = `https://${workerName}.${accountSubdomain.subdomain}.workers.dev`;
await mkdir(new URL("../../config/", import.meta.url), { recursive: true });
await writeFile(
  new URL("../../config/runtime.json", import.meta.url),
  `${JSON.stringify({ cloudflare_url: baseUrl }, null, 2)}\n`,
  "utf8",
);
console.log(`[discover] Worker URL: ${baseUrl}`);

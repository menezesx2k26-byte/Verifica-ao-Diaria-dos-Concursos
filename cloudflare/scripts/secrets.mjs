import { writeFile } from "node:fs/promises";
import process from "node:process";

const required = ["WATCHDOG_TOKEN"];
for (const key of required) {
  if (!(process.env[key] || "").trim()) {
    throw new Error(`${key} é obrigatório para o deploy do Worker.`);
  }
}

const names = [
  "WATCHDOG_TOKEN",
  "TELEGRAM_BOT_TOKEN",
  "TELEGRAM_CHAT_ID",
  "NTFY_TOPIC",
  "NTFY_SERVER",
  "NTFY_TOKEN",
  "CF_EMAIL_FROM",
  "CF_EMAIL_TO",
];

const payload = {};
for (const name of names) {
  const value = process.env[name];
  if (value && value.trim()) payload[name] = value;
}

await writeFile(
  new URL("../.secrets.generated.json", import.meta.url),
  `${JSON.stringify(payload)}\n`,
  { encoding: "utf8", mode: 0o600 },
);

console.log(`[secrets] ${Object.keys(payload).length} secrets preparados sem imprimir valores.`);

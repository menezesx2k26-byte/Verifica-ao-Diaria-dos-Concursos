import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";

const baseUrl = process.env.DASHBOARD_PREVIEW_URL || "http://127.0.0.1:4173/dashboard.html";
const outputDir = process.env.DASHBOARD_VISUAL_DIR || "visual-qa";
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
try {
  const cases = [
    ["dashboard-phone.png", { width: 412, height: 915 }],
    ["dashboard-wide.png", { width: 1080, height: 1920 }],
  ];
  for (const [name, viewport] of cases) {
    const context = await browser.newContext({
      viewport,
      javaScriptEnabled: false,
      colorScheme: "dark",
    });
    const page = await context.newPage();
    const response = await page.goto(baseUrl, { waitUntil: "networkidle" });
    if (!response?.ok()) throw new Error(`preview HTTP ${response?.status()}`);
    if ((await page.locator("script").count()) !== 0) {
      throw new Error("dashboard preview contains script elements");
    }
    await page.screenshot({ path: `${outputDir}/${name}`, fullPage: true });
    await context.close();
  }
} finally {
  await browser.close();
}

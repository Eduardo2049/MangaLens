const http = require("node:http");
const fs = require("node:fs");
const crypto = require("node:crypto");

const DEFAULT_PORT = 8080;
const DEFAULT_MODEL = "gemini-3.8-flash";
const MAX_IMAGE_BASE64_LENGTH = 12 * 1024 * 1024;
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_MAX_REQUESTS = 30;

function loadDotEnv() {
  const envPath = require("node:path").join(__dirname, "..", ".env");
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (match && !process.env[match[1]]) process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, "");
  }
}

function createPrompt(sourceLanguage, targetLanguage) {
  const source = sourceLanguage === "auto" ? "Detect automatically" : sourceLanguage;
  return `You are a high-precision OCR and visual translator for manga, manhwa, and webtoons.
Analyze the image and find every readable text region, including speech bubbles, narration, sound effects, and vertical text.
Translate from ${source} to ${targetLanguage}.
Return only valid JSON with this exact shape: {"overlays":[{"originalText":"...","translatedText":"...","yMin":0,"xMin":0,"yMax":0,"xMax":0,"isVertical":false,"bubbleType":"SPEECH"}]}.
Coordinates must be integers from 0 to 100 and represent percentages of the image dimensions.`;
}

function extractJson(text) {
  const trimmed = String(text || "").trim();
  const start = Math.min(...[trimmed.indexOf("["), trimmed.indexOf("{")].filter((index) => index >= 0));
  if (start === Infinity) throw new Error("Gemini returned no JSON payload");
  const end = Math.max(trimmed.lastIndexOf("]"), trimmed.lastIndexOf("}"));
  if (end <= start) throw new Error("Gemini returned incomplete JSON");
  return JSON.parse(trimmed.slice(start, end + 1));
}

function normalizeOverlays(payload) {
  const overlays = Array.isArray(payload) ? payload : payload.overlays || payload.items || payload.textRegions;
  if (!Array.isArray(overlays)) throw new Error("Gemini returned an invalid overlays payload");

  return overlays.flatMap((item) => {
    const originalText = String(item.originalText || item.text || "").trim();
    const translatedText = String(item.translatedText || item.translation || "").trim();
    if (!originalText || !translatedText) return [];

    const clamp = (value, fallback) => {
      const number = Number(value);
      return Number.isFinite(number) ? Math.min(100, Math.max(0, number)) : fallback;
    };
    const yMin = clamp(item.yMin, 0);
    const xMin = clamp(item.xMin, 0);
    const yMax = Math.max(yMin, clamp(item.yMax, yMin + 10));
    const xMax = Math.max(xMin, clamp(item.xMax, xMin + 15));

    return [{
      originalText,
      translatedText,
      yMin,
      xMin,
      yMax,
      xMax,
      isVertical: Boolean(item.isVertical),
      bubbleType: String(item.bubbleType || "SPEECH")
    }];
  });
}

async function translateImage({ imageBase64, sourceLanguage, targetLanguage }, options = {}) {
  const apiKey = options.apiKey || process.env.GEMINI_API_KEY;
  const model = options.model || process.env.GEMINI_MODEL || DEFAULT_MODEL;
  const fetchImpl = options.fetchImpl || fetch;

  if (!apiKey || apiKey === "MY_GEMINI_API_KEY") throw new Error("GEMINI_API_KEY is not configured");
  if (typeof imageBase64 !== "string" || imageBase64.length === 0 || imageBase64.length > MAX_IMAGE_BASE64_LENGTH) {
    throw new Error("imageBase64 must be a non-empty image smaller than 9 MB");
  }
  if (typeof targetLanguage !== "string" || !targetLanguage.trim()) throw new Error("targetLanguage is required");

  const response = await fetchImpl(
    `https://generativelanguage.googleapis.com/v1/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        contents: [{
          parts: [
            { text: createPrompt(sourceLanguage || "auto", targetLanguage) },
            { inline_data: { mime_type: "image/jpeg", data: imageBase64 } }
          ]
        }],
        generationConfig: { responseMimeType: "application/json", temperature: 0.2 }
      })
    }
  );
  const body = await response.json();
  if (!response.ok) throw new Error(body.error?.message || `Gemini returned HTTP ${response.status}`);

  const text = body.candidates?.[0]?.content?.parts?.map((part) => part.text || "").join("") || "";
  return normalizeOverlays(extractJson(text));
}

function createServer(options = {}) {
  const requestCounts = new Map();
  const translate = options.translate || translateImage;

  return http.createServer(async (request, response) => {
    const requestId = crypto.randomUUID();
    const startedAt = Date.now();
    const logResult = (status, message) => {
      console.log(`[${requestId}] ${request.method} ${request.url} -> ${status} (${Date.now() - startedAt}ms)${message ? ` ${message}` : ""}`);
    };
    response.setHeader("x-request-id", requestId);
    response.setHeader("access-control-allow-origin", options.allowedOrigin || "*");
    response.setHeader("access-control-allow-headers", "content-type");
    response.setHeader("access-control-allow-methods", "POST, GET, OPTIONS");
    if (request.method === "OPTIONS") return response.writeHead(204).end();
    if (request.url === "/health" && request.method === "GET") {
      logResult(200);
      return response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ ok: true }));
    }
    if (request.url !== "/v1/translate" || request.method !== "POST") {
      logResult(404, "route-not-found");
      return response.writeHead(404, { "content-type": "application/json" }).end(JSON.stringify({ error: "Not found" }));
    }

    const address = request.socket.remoteAddress || "unknown";
    const now = Date.now();
    const recent = (requestCounts.get(address) || []).filter((timestamp) => now - timestamp < RATE_LIMIT_WINDOW_MS);
    if (recent.length >= RATE_LIMIT_MAX_REQUESTS) {
      logResult(429, "rate-limit");
      return response.writeHead(429, { "content-type": "application/json" }).end(JSON.stringify({ error: "Rate limit exceeded", requestId }));
    }
    requestCounts.set(address, [...recent, now]);

    try {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const overlays = await translate(payload, options.translateOptions);
      logResult(200, `overlays=${overlays.length}`);
      response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify({ overlays, requestId }));
    } catch (error) {
      const status = /required|configured|imageBase64|invalid|smaller/.test(error.message) ? 400 : 502;
      console.error(`[${requestId}] ${request.method} ${request.url} failed: ${error.stack || error.message}`);
      response.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify({ error: error.message, requestId }));
    }
  });
}

if (require.main === module) {
  loadDotEnv();
  const port = Number(process.env.PORT || DEFAULT_PORT);
  createServer().listen(port, "0.0.0.0", () => console.log(`MangaLens backend listening on port ${port}`));
}

module.exports = { createServer, extractJson, normalizeOverlays, translateImage };
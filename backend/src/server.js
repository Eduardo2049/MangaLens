const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const DEFAULT_PORT = 8080;
const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const MAX_BODY_BYTES = 12 * 1024 * 1024;
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
// OpenRouter free tier allows 20 requests/minute; stay just below it.
const RATE_LIMIT_MAX_REQUESTS = 18;
const PASSTHROUGH_HEADERS = ["retry-after", "x-ratelimit-limit", "x-ratelimit-remaining", "x-ratelimit-reset"];

function loadDotEnv() {
  const envPath = path.join(__dirname, "..", ".env");
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (match && !process.env[match[1]]) process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, "");
  }
}

/** Only free models may pass, so a misconfigured app can never spend credits. */
function isFreeModel(model) {
  return typeof model === "string" && (model.endsWith(":free") || model === "openrouter/free");
}

function validateChatRequest(payload) {
  if (!payload || typeof payload !== "object") throw new Error("invalid JSON body");
  if (!Array.isArray(payload.messages) || payload.messages.length === 0) throw new Error("messages are required");
  const models = payload.models || (payload.model ? [payload.model] : []);
  if (!Array.isArray(models) || models.length === 0) throw new Error("models are required");
  const blocked = models.filter((model) => !isFreeModel(model));
  if (blocked.length > 0) throw new Error(`invalid model (only free models allowed): ${blocked.join(", ")}`);
  return { ...payload, models, model: undefined };
}

async function forwardChat(payload, options = {}) {
  const apiKey = options.apiKey || process.env.OPENROUTER_API_KEY;
  const fetchImpl = options.fetchImpl || fetch;
  if (!apiKey || apiKey === "MY_OPENROUTER_API_KEY") throw new Error("OPENROUTER_API_KEY is not configured");

  const response = await fetchImpl(OPENROUTER_URL, {
    method: "POST",
    headers: {
      authorization: `Bearer ${apiKey}`,
      "content-type": "application/json",
      "x-title": "MangaLens"
    },
    body: JSON.stringify(validateChatRequest(payload))
  });
  const headers = {};
  for (const name of PASSTHROUGH_HEADERS) {
    const value = response.headers.get(name);
    if (value) headers[name] = value;
  }
  return { status: response.status, headers, body: await response.text() };
}

function createServer(options = {}) {
  const requestCounts = new Map();
  const forward = options.forward || forwardChat;

  return http.createServer(async (request, response) => {
    const requestId = crypto.randomUUID();
    const startedAt = Date.now();
    const logResult = (status, message) => {
      console.log(`[${requestId}] ${request.method} ${request.url} -> ${status} (${Date.now() - startedAt}ms)${message ? ` ${message}` : ""}`);
    };
    const sendJson = (status, body, headers = {}) =>
      response.writeHead(status, { "content-type": "application/json", ...headers }).end(JSON.stringify(body));

    response.setHeader("x-request-id", requestId);
    if (request.url === "/health" && request.method === "GET") {
      logResult(200);
      return sendJson(200, { ok: true });
    }
    if (request.url !== "/v1/chat/completions" || request.method !== "POST") {
      logResult(404, "route-not-found");
      return sendJson(404, { error: { message: "Not found" } });
    }

    const address = request.socket.remoteAddress || "unknown";
    const now = Date.now();
    const recent = (requestCounts.get(address) || []).filter((timestamp) => now - timestamp < RATE_LIMIT_WINDOW_MS);
    if (recent.length >= RATE_LIMIT_MAX_REQUESTS) {
      const retryAfter = Math.ceil((RATE_LIMIT_WINDOW_MS - (now - recent[0])) / 1000);
      logResult(429, "local-rate-limit");
      return sendJson(429, { error: { code: 429, message: "Rate limit exceeded", requestId } }, { "retry-after": String(retryAfter) });
    }
    requestCounts.set(address, [...recent, now]);

    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) throw new Error("invalid request: body too large");
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const result = await forward(payload, options.forwardOptions);
      logResult(result.status, `models=${(payload.models || [payload.model]).join(",")}`);
      response.writeHead(result.status, { "content-type": "application/json", ...result.headers }).end(result.body);
    } catch (error) {
      const status = /required|configured|invalid|JSON/.test(error.message) ? 400 : 502;
      console.error(`[${requestId}] ${request.method} ${request.url} failed: ${error.stack || error.message}`);
      sendJson(status, { error: { message: error.message, requestId } });
    }
  });
}

if (require.main === module) {
  loadDotEnv();
  const port = Number(process.env.PORT || DEFAULT_PORT);
  createServer().listen(port, "0.0.0.0", () => console.log(`MangaLens backend listening on port ${port}`));
}

module.exports = { createServer, forwardChat, isFreeModel, validateChatRequest };

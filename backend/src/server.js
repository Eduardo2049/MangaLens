const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");

const DEFAULT_PORT = 8080;
const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const MAX_BODY_BYTES = 12 * 1024 * 1024;
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
// OpenRouter free tier allows 20 requests/minute PER KEY: every app user shares this server's key,
// so the global limit protects the key and the per-client limit keeps one phone from using it all.
const GLOBAL_RATE_LIMIT = 18;
const PER_CLIENT_RATE_LIMIT = 10;
const APP_TOKEN_HEADER = "x-mangalens-token";
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

/**
 * Sends the request to OpenRouter. A streamed answer (`stream: true`) is returned as a web stream so the
 * server can relay each bubble as soon as the model writes it, instead of waiting for the whole page.
 */
async function forwardChat(payload, options = {}) {
  const apiKey = options.apiKey || process.env.OPENROUTER_API_KEY;
  const fetchImpl = options.fetchImpl || fetch;
  if (!apiKey || apiKey === "MY_OPENROUTER_API_KEY") throw new Error("OPENROUTER_API_KEY is not configured");

  const body = validateChatRequest(payload);
  const response = await fetchImpl(OPENROUTER_URL, {
    method: "POST",
    headers: {
      authorization: `Bearer ${apiKey}`,
      "content-type": "application/json",
      "x-title": "MangaLens"
    },
    body: JSON.stringify(body)
  });
  const headers = {};
  for (const name of PASSTHROUGH_HEADERS) {
    const value = response.headers.get(name);
    if (value) headers[name] = value;
  }
  const contentType = response.headers.get("content-type");
  if (body.stream === true && response.status === 200 && response.body) {
    headers["content-type"] = contentType || "text/event-stream";
    headers["cache-control"] = "no-cache";
    return { status: response.status, headers, stream: response.body };
  }
  if (contentType) headers["content-type"] = contentType;
  return { status: response.status, headers, body: await response.text() };
}

/** Constant-time comparison so the token cannot be guessed byte by byte from response timings. */
function tokenMatches(expected, received) {
  if (typeof received !== "string") return false;
  const a = crypto.createHash("sha256").update(expected).digest();
  const b = crypto.createHash("sha256").update(received).digest();
  return crypto.timingSafeEqual(a, b);
}

/**
 * Client address for rate limiting. Behind a reverse proxy (Render, Railway, Fly, nginx...) every request
 * comes from the proxy's address; with TRUST_PROXY=1 the first X-Forwarded-For entry is used instead.
 * Only enable it behind a proxy that sets the header, otherwise clients could choose their own address.
 */
function clientAddress(request, trustProxy) {
  if (trustProxy) {
    const forwarded = request.headers["x-forwarded-for"];
    const first = typeof forwarded === "string" ? forwarded.split(",")[0].trim() : "";
    if (first) return first;
  }
  return request.socket.remoteAddress || "unknown";
}

/** Sliding-window counters; idle clients are dropped so the map does not grow forever. */
function createRateLimiter({ windowMs, perClient, global, now }) {
  const clients = new Map();
  let globalHits = [];
  let lastSweep = now();

  const sweep = (time) => {
    for (const [address, hits] of clients) {
      const recent = hits.filter((timestamp) => time - timestamp < windowMs);
      if (recent.length === 0) clients.delete(address);
      else clients.set(address, recent);
    }
    lastSweep = time;
  };

  return {
    get trackedClients() {
      return clients.size;
    },
    /** Returns null when allowed (and records the hit), or the seconds to wait. */
    hit(address) {
      const time = now();
      if (time - lastSweep >= windowMs) sweep(time);
      globalHits = globalHits.filter((timestamp) => time - timestamp < windowMs);
      const recent = (clients.get(address) || []).filter((timestamp) => time - timestamp < windowMs);
      const blockedBy = recent.length >= perClient ? recent : globalHits.length >= global ? globalHits : null;
      if (blockedBy) return Math.max(1, Math.ceil((windowMs - (time - blockedBy[0])) / 1000));
      recent.push(time);
      clients.set(address, recent);
      globalHits.push(time);
      return null;
    }
  };
}

function createServer(options = {}) {
  const forward = options.forward || forwardChat;
  const appToken = options.appToken !== undefined ? options.appToken : process.env.MANGALENS_APP_TOKEN || "";
  const trustProxy = options.trustProxy !== undefined ? options.trustProxy : process.env.TRUST_PROXY === "1";
  const limiter = createRateLimiter({
    windowMs: options.windowMs || RATE_LIMIT_WINDOW_MS,
    perClient: options.perClientLimit || Number(process.env.PER_CLIENT_RATE_LIMIT) || PER_CLIENT_RATE_LIMIT,
    global: options.globalLimit || Number(process.env.GLOBAL_RATE_LIMIT) || GLOBAL_RATE_LIMIT,
    now: options.now || Date.now
  });
  if (!appToken) {
    console.warn("MANGALENS_APP_TOKEN is not set: anyone who finds this URL can use the OpenRouter key.");
  }

  const server = http.createServer(async (request, response) => {
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
    // Checked before the rate limit: requests without the token must not use up anybody's quota.
    if (appToken && !tokenMatches(appToken, request.headers[APP_TOKEN_HEADER])) {
      logResult(401, "bad-app-token");
      return sendJson(401, { error: { code: 401, message: "Unauthorized", requestId } });
    }

    const retryAfter = limiter.hit(clientAddress(request, trustProxy));
    if (retryAfter !== null) {
      logResult(429, "local-rate-limit");
      return sendJson(429, { error: { code: 429, message: "Rate limit exceeded", requestId } }, { "retry-after": String(retryAfter) });
    }

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
      const models = (payload.models || [payload.model]).join(",");
      response.writeHead(result.status, { "content-type": "application/json", ...result.headers });
      if (result.stream) {
        logResult(result.status, `models=${models} stream`);
        const upstream = result.stream instanceof Readable ? result.stream : Readable.fromWeb(result.stream);
        // If the phone disconnects (user scrolled away), stop reading from OpenRouter too.
        await pipeline(upstream, response).catch((error) => {
          console.warn(`[${requestId}] stream ended early: ${error.message}`);
        });
      } else {
        logResult(result.status, `models=${models}`);
        response.end(result.body);
      }
    } catch (error) {
      const status = /required|configured|invalid|JSON/.test(error.message) ? 400 : 502;
      console.error(`[${requestId}] ${request.method} ${request.url} failed: ${error.stack || error.message}`);
      if (response.headersSent) return response.destroy(error);
      sendJson(status, { error: { message: error.message, requestId } });
    }
  });
  server.rateLimiter = limiter;
  return server;
}

if (require.main === module) {
  loadDotEnv();
  const port = Number(process.env.PORT || DEFAULT_PORT);
  createServer().listen(port, "0.0.0.0", () => console.log(`MangaLens backend listening on port ${port}`));
}

module.exports = { createServer, createRateLimiter, clientAddress, forwardChat, isFreeModel, validateChatRequest, APP_TOKEN_HEADER };

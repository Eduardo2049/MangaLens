const test = require("node:test");
const assert = require("node:assert");
const { createServer, createRateLimiter, clientAddress, forwardChat, validateChatRequest, APP_TOKEN_HEADER } = require("../src/server");

const chatBody = {
  models: ["google/gemma-4-31b-it:free", "openrouter/free"],
  messages: [{ role: "user", content: "hi" }]
};

test("rejects paid models", () => {
  assert.throws(() => validateChatRequest({ ...chatBody, models: ["openai/gpt-5"] }), /only free models/);
});

test("forwards to OpenRouter with the server key and passes rate-limit headers through", async () => {
  let captured;
  const fetchImpl = async (url, init) => {
    captured = { url, init };
    return {
      status: 429,
      headers: new Map([["retry-after", "12"]]),
      text: async () => JSON.stringify({ error: { code: 429, message: "Rate limit exceeded" } })
    };
  };
  const result = await forwardChat(chatBody, { apiKey: "sk-test", fetchImpl });
  assert.strictEqual(captured.url, "https://openrouter.ai/api/v1/chat/completions");
  assert.strictEqual(captured.init.headers.authorization, "Bearer sk-test");
  assert.deepStrictEqual(JSON.parse(captured.init.body).models, chatBody.models);
  assert.strictEqual(result.status, 429);
  assert.strictEqual(result.headers["retry-after"], "12");
});

test("server proxies chat completions", async (t) => {
  const server = createServer({
    forward: async (payload) => ({ status: 200, headers: {}, body: JSON.stringify({ echoed: payload.models }) })
  });
  await new Promise((resolve) => server.listen(0, resolve));
  t.after(() => server.close());
  const { port } = server.address();

  const ok = await fetch(`http://127.0.0.1:${port}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(chatBody)
  });
  assert.strictEqual(ok.status, 200);
  assert.deepStrictEqual((await ok.json()).echoed, chatBody.models);

  const health = await fetch(`http://127.0.0.1:${port}/health`);
  assert.deepStrictEqual(await health.json(), { ok: true });
});

async function listen(server, t) {
  await new Promise((resolve) => server.listen(0, resolve));
  t.after(() => server.close());
  return `http://127.0.0.1:${server.address().port}`;
}

const okForward = async () => ({ status: 200, headers: {}, body: "{}" });
const post = (url, headers = {}) =>
  fetch(`${url}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(chatBody)
  });

test("app token is required when configured and does not consume the quota when missing", async (t) => {
  const server = createServer({ forward: okForward, appToken: "segredo", globalLimit: 1 });
  const url = await listen(server, t);
  assert.strictEqual((await post(url)).status, 401);
  assert.strictEqual((await post(url, { [APP_TOKEN_HEADER]: "errado" })).status, 401);
  // The two rejected calls above must not have used the single allowed request.
  assert.strictEqual((await post(url, { [APP_TOKEN_HEADER]: "segredo" })).status, 200);
});

test("behind a proxy each phone is limited by its own X-Forwarded-For address", async (t) => {
  const server = createServer({ forward: okForward, appToken: "", trustProxy: true, perClientLimit: 1, globalLimit: 10 });
  const url = await listen(server, t);
  assert.strictEqual((await post(url, { "x-forwarded-for": "1.1.1.1" })).status, 200);
  assert.strictEqual((await post(url, { "x-forwarded-for": "2.2.2.2, 10.0.0.1" })).status, 200);
  const limited = await post(url, { "x-forwarded-for": "1.1.1.1" });
  assert.strictEqual(limited.status, 429);
  assert.ok(Number(limited.headers.get("retry-after")) >= 1);
});

test("without TRUST_PROXY the forwarded header is ignored (cannot be spoofed)", () => {
  const request = { headers: { "x-forwarded-for": "9.9.9.9" }, socket: { remoteAddress: "127.0.0.1" } };
  assert.strictEqual(clientAddress(request, false), "127.0.0.1");
  assert.strictEqual(clientAddress(request, true), "9.9.9.9");
});

test("global limit protects the shared OpenRouter key across all clients", () => {
  const limiter = createRateLimiter({ windowMs: 60_000, perClient: 5, global: 2, now: () => 1_000 });
  assert.strictEqual(limiter.hit("a"), null);
  assert.strictEqual(limiter.hit("b"), null);
  assert.ok(limiter.hit("c") >= 1);
});

test("idle clients are forgotten after the window", () => {
  let time = 0;
  const limiter = createRateLimiter({ windowMs: 60_000, perClient: 5, global: 100, now: () => time });
  for (let i = 0; i < 50; i++) limiter.hit(`10.0.0.${i}`);
  assert.strictEqual(limiter.trackedClients, 50);
  time = 61_000;
  limiter.hit("10.0.1.1");
  assert.strictEqual(limiter.trackedClients, 1);
});

test("streamed answers are relayed chunk by chunk, not buffered", async (t) => {
  const encoder = new TextEncoder();
  const chunks = ['data: {"choices":[{"delta":{"content":"{\\"t\\":[[1,"}}]}\n\n', 'data: {"choices":[{"delta":{"content":"\\"Olá\\"]]}"}}]}\n\n', "data: [DONE]\n\n"];
  const fetchImpl = async (_url, init) => {
    assert.strictEqual(JSON.parse(init.body).stream, true);
    return {
      status: 200,
      headers: new Map([["content-type", "text/event-stream"]]),
      body: new ReadableStream({
        start(controller) {
          chunks.forEach((c) => controller.enqueue(encoder.encode(c)));
          controller.close();
        }
      })
    };
  };
  const server = createServer({ appToken: "", forwardOptions: { apiKey: "sk-test", fetchImpl } });
  const url = await listen(server, t);
  const response = await fetch(`${url}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...chatBody, stream: true })
  });
  assert.strictEqual(response.status, 200);
  assert.match(response.headers.get("content-type"), /text\/event-stream/);
  assert.strictEqual(await response.text(), chunks.join(""));
});

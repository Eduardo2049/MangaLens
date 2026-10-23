const test = require("node:test");
const assert = require("node:assert");
const { createServer, forwardChat, validateChatRequest } = require("../src/server");

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

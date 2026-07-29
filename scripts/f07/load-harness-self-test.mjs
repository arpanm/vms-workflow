import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { runProfile } from "./load-harness.mjs";

test("actor credentials and runtime values stay outside the checked-in profile", async () => {
  const received = [];
  const server = createServer(async (request, response) => {
    let body = "";
    for await (const chunk of request) {
      body += chunk;
    }
    received.push({
      authorization: request.headers.authorization,
      body: JSON.parse(body),
      idempotencyKey: request.headers["idempotency-key"],
    });
    response.writeHead(201, { "content-type": "application/json" });
    response.end('{"status":"accepted"}');
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const address = server.address();
    const report = await runProfile(
      `http://127.0.0.1:${address.port}`,
      {
        budget: { maximumErrorRate: 0, p95Ms: 1000 },
        concurrency: 2,
        id: "actor-profile-test",
        iterations: 2,
        requests: [
          {
            auth: "actor",
            body: {
              employeeId: "${actorId}",
              idempotencyKey: "${idempotencyKey}",
            },
            method: "POST",
            path: "/api/v1/attendance/punches",
          },
        ],
        requiresActors: 2,
      },
      true,
      {
        actorBearerTokens: ["token-one", "token-two"],
        actorIds: ["employee-one", "employee-two"],
      },
    );

    assert.equal(report.result, "PASS");
    assert.equal(report.requests, 2);
    assert.deepEqual(
      received.map((value) => value.authorization).sort(),
      ["Bearer token-one", "Bearer token-two"],
    );
    assert.deepEqual(
      received.map((value) => value.body.employeeId).sort(),
      ["employee-one", "employee-two"],
    );
    assert.equal(new Set(received.map((value) => value.idempotencyKey)).size, 2);
    assert.ok(received.every(
      (value) => value.body.idempotencyKey === value.idempotencyKey,
    ));
    assert.equal(JSON.stringify(report).includes("token-one"), false);
  } finally {
    server.close();
    await once(server, "close");
  }
});

test("actor cardinality is enforced before a load starts", async () => {
  await assert.rejects(
    runProfile(
      "http://127.0.0.1:9",
      {
        concurrency: 1,
        id: "actor-cardinality-test",
        iterations: 1,
        requests: [{ method: "GET", path: "/" }],
        requiresActors: 2,
      },
      false,
      { actorIds: ["only-one"] },
    ),
    /requires exactly 2 actor IDs/,
  );
});

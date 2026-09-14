import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { testEnv } from "./helpers";

describe("worker smoke", () => {
  it("answers the health probe and has all bindings", async () => {
    const response = await SELF.fetch("https://guardian.test/v1/health");
    expect(response.status).toBe(200);
    const body = (await response.json()) as { data: { status: string; configured: boolean } };
    expect(body.data.status).toBe("ok");
    expect(body.data.configured).toBe(true);
    expect(testEnv.DB).toBeTruthy();
    expect(testEnv.EVIDENCE).toBeTruthy();
    expect(testEnv.SAFETY_HUB).toBeTruthy();
  });
});

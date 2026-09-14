import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

export default defineConfig(async () => {
  const migrations = await readD1Migrations("./migrations");
  return {
    plugins: [
      cloudflareTest({
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          bindings: {
            AUTH_SECRET: "test-secret-value-not-used-in-production",
            TEST_MIGRATIONS: migrations,
            // Small evidence limit so size enforcement is exercised in tests.
            MAX_EVIDENCE_BYTES: "2048",
          },
        },
      }),
    ],
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
    },
  };
});

import { applyD1Migrations, env } from "cloudflare:test";

interface TestMigration {
  name: string;
  queries: string[];
}

const bindings = env as unknown as { DB: D1Database; TEST_MIGRATIONS: TestMigration[] };

// Applies every file in ./migrations to the isolated per-test D1 database.
await applyD1Migrations(bindings.DB, bindings.TEST_MIGRATIONS);

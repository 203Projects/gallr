import { readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";

// The supplied local disposable container is the only target. Every operation,
// including migration reapplication, runs in the fixture's rolled-back transaction.
const container = process.env.SUPABASE_DB_CONTAINER;
if (!container || !/^supabase_db_[a-z0-9_-]+$/.test(container)) {
  throw new Error("Set SUPABASE_DB_CONTAINER to a disposable local Supabase database.");
}
const endpoint = process.env.DOCKER_HOST ?? execFileSync("docker", ["context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], { encoding: "utf8" }).trim();
if (!endpoint.startsWith("unix://")) throw new Error("Only a local Unix-socket Docker engine is allowed.");
const dockerEnv = { ...process.env };
delete dockerEnv.DOCKER_CONTEXT;
const migration = readFileSync(new URL("../migrations/20260922023714_complete_workflow_email_delivery.sql", import.meta.url), "utf8");
const fixture = readFileSync(new URL("./fixtures/claim-email-backfill.sql", import.meta.url), "utf8");
const query = fixture.replaceAll("\\ir ../../migrations/20260922023714_complete_workflow_email_delivery.sql", () => migration);
const output = execFileSync("docker", ["--host", endpoint, "exec", "-i", container, "psql", "-U", "postgres", "-d", "postgres", "-XAt", "-v", "ON_ERROR_STOP=1"], { input: query, encoding: "utf8", env: dockerEnv });
console.log(output.split("\n").filter(line => /^(ok |not ok |#|1\.\.)/.test(line)).join("\n"));
if (/^not ok /m.test(output) || !/^1\.\.2$/m.test(output)) process.exitCode = 1;

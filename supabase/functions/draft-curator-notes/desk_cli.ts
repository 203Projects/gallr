import { loadPromptAssets, type PromptAssets } from "./drafting.ts";
import {
  briefFor,
  candidatesSql,
  checkDrafts,
  DeskUsageError,
  type DeskVerdict,
  lookupSql,
  overrideSet,
  parseCandidates,
  parseDrafts,
  storeSql,
} from "./desk.ts";

/** What the command needs from the outside world; tests substitute a fake. */
export interface DeskIo {
  readFile(path: string): Promise<string>;
  assets(): Promise<PromptAssets>;
  now(): number;
  stdout(text: string): void;
  stderr(text: string): void;
}

const USAGE = `Usage:
  deno task desk lookup-sql --name-ko <fragment>
  deno task desk candidates-sql [--limit N] [--exhibition ID]...
  deno task desk brief <candidates.json> [--override ID]...
  deno task desk check <candidates.json> <drafts.json> [--override ID]...
  deno task desk store-sql <candidates.json> <drafts.json> --model <model-id> --requested-by <staff-auth-uid> [--override ID]...

Every command prints to standard output and changes nothing. --override names one exhibition to
store over an editor's decline or an approved note; give it once per exhibition, on the operator's word.
Exit 0: done. Exit 1: a draft was refused (the verdicts are on standard output). Exit 2: wrong arguments.
Exit 3: an input file could not be read, or holds nothing to store (the reason is on standard error).
See README.md, "Desk drafting".
`;

const EXIT_OK = 0;
const EXIT_REJECTED = 1;
const EXIT_USAGE = 2;
const EXIT_INPUT = 3;
const INTEGER = /^\d+$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface Arguments {
  positional: string[];
  options: Map<string, string[]>;
}

function parse(args: string[]): Arguments | null {
  const positional: string[] = [];
  const options = new Map<string, string[]>();
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (!arg.startsWith("--")) {
      positional.push(arg);
      continue;
    }
    const value = args[index + 1];
    if (value === undefined || value.startsWith("--")) return null;
    options.set(arg, [...(options.get(arg) ?? []), value]);
    index += 1;
  }
  return { positional, options };
}

function only(args: Arguments, allowed: string[]): boolean {
  return [...args.options.keys()].every((name) => allowed.includes(name));
}

function printVerdicts(verdicts: DeskVerdict[], io: DeskIo): boolean {
  for (const verdict of verdicts) {
    const outcome = verdict.code ??
      (verdict.overridden ? "ok (override)" : "ok");
    io.stdout(`${verdict.exhibitionId}\t${outcome}\n`);
  }
  return verdicts.some((verdict) => !verdict.ok && verdict.code !== "no_draft");
}

/** Runs one desk command and returns its exit code; see USAGE for the meaning of each code. */
export async function runDesk(args: string[], io: DeskIo): Promise<number> {
  const [command, ...rest] = args;
  const parsed = parse(rest);
  const usage = (): number => {
    io.stderr(USAGE);
    return EXIT_USAGE;
  };
  if (!parsed) return usage();
  const { positional, options } = parsed;
  try {
    const override = overrideSet(options.get("--override") ?? []);
    if (command === "lookup-sql") {
      const fragment = options.get("--name-ko")?.at(-1);
      if (positional.length > 0 || !only(parsed, ["--name-ko"]) || !fragment) {
        return usage();
      }
      io.stdout(lookupSql(fragment));
      return EXIT_OK;
    }
    if (command === "candidates-sql") {
      if (positional.length > 0 || !only(parsed, ["--limit", "--exhibition"])) {
        return usage();
      }
      const ids = options.get("--exhibition");
      const limit = options.get("--limit")?.at(-1);
      if (ids && limit !== undefined) return usage();
      if (limit !== undefined && !INTEGER.test(limit)) return usage();
      io.stdout(
        candidatesSql(
          ids
            ? { exhibitionIds: ids }
            : { limit: limit === undefined ? undefined : Number(limit) },
        ),
      );
      return EXIT_OK;
    }
    if (command === "brief") {
      if (positional.length !== 1 || !only(parsed, ["--override"])) {
        return usage();
      }
      const candidates = parseCandidates(await io.readFile(positional[0]));
      io.stdout(briefFor(candidates, await io.assets(), { override }));
      return EXIT_OK;
    }
    if (command === "check") {
      if (positional.length !== 2 || !only(parsed, ["--override"])) {
        return usage();
      }
      const candidates = parseCandidates(await io.readFile(positional[0]));
      const drafts = parseDrafts(await io.readFile(positional[1]));
      const refused = printVerdicts(
        checkDrafts(candidates, drafts, { override }),
        io,
      );
      return refused ? EXIT_REJECTED : EXIT_OK;
    }
    if (command === "store-sql") {
      const model = options.get("--model")?.at(-1);
      const requestedBy = options.get("--requested-by")?.at(-1);
      if (
        positional.length !== 2 ||
        !only(parsed, ["--model", "--requested-by", "--override"]) ||
        !model || !requestedBy
      ) {
        return usage();
      }
      if (!model.trim() || !UUID.test(requestedBy)) return usage();
      const candidates = parseCandidates(await io.readFile(positional[0]));
      const drafts = parseDrafts(await io.readFile(positional[1]));
      const verdicts = checkDrafts(candidates, drafts, { override });
      if (verdicts.some((v) => !v.ok && v.code !== "no_draft")) {
        printVerdicts(verdicts, io);
        return EXIT_REJECTED;
      }
      io.stdout(
        storeSql(candidates, drafts, {
          model,
          requestedBy,
          override,
          generatedAt: new Date(io.now()).toISOString(),
        }),
      );
      return EXIT_OK;
    }
  } catch (error) {
    if (error instanceof DeskUsageError) {
      io.stderr(`${error.message}\n`);
      return usage();
    }
    io.stderr(`${error instanceof Error ? error.message : "Failed."}\n`);
    return EXIT_INPUT;
  }
  return usage();
}

if (import.meta.main) {
  const encoder = new TextEncoder();
  Deno.exit(
    await runDesk(Deno.args, {
      readFile: (path) => Deno.readTextFile(path),
      assets: loadPromptAssets,
      now: () => Date.now(),
      stdout: (text) => Deno.stdout.writeSync(encoder.encode(text)),
      stderr: (text) => Deno.stderr.writeSync(encoder.encode(text)),
    }),
  );
}

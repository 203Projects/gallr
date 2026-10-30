import {
  briefFor,
  candidatesSql,
  checkDrafts,
  isRefusal,
  parseCandidates,
  parseDrafts,
  sheetFor,
  TagUsageError,
  type TagVerdict,
} from "./tag.ts";

/** What the command needs from the outside world; tests substitute a fake. */
export interface TagIo {
  readFile(path: string): Promise<string>;
  stdout(text: string): void;
  stderr(text: string): void;
}

const USAGE = `Usage:
  deno task -q tag candidates-sql [--limit N | --exhibition ID...]
  deno task -q tag brief <candidates.json>
  deno task -q tag check <candidates.json> <drafts.json>
  deno task -q tag sheet <candidates.json> <drafts.json>

Every command prints to standard output, reads only the files it is given and changes nothing; the SQL
it prints is read-only. Without --exhibition, candidates-sql reads up to N (default 10, at most 50)
running and upcoming exhibitions with fewer than three reviewed art terms or stale metadata.
Exit 0: done. Exit 1: a draft was refused (the verdicts are on standard output). Exit 2: wrong arguments.
Exit 3: an input file could not be read, or holds nothing to apply (the reason is on standard error).
See README.md.
`;

const EXIT_OK = 0;
const EXIT_REJECTED = 1;
const EXIT_USAGE = 2;
const EXIT_INPUT = 3;
const INTEGER = /^\d+$/;

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

/** One line per verdict, or one per problem: `<id>\t<code>[\t<subject as JSON>]`. */
function printVerdicts(verdicts: TagVerdict[], io: TagIo): boolean {
  for (const verdict of verdicts) {
    if (verdict.ok) {
      io.stdout(`${verdict.exhibitionId}\tok\n`);
      continue;
    }
    for (const problem of verdict.problems) {
      const subject = problem.subject === null
        ? ""
        : `\t${JSON.stringify(problem.subject)}`;
      io.stdout(`${verdict.exhibitionId}\t${problem.code}${subject}\n`);
    }
  }
  return verdicts.some(isRefusal);
}

/** Runs one tag command and returns its exit code; see USAGE for the meaning of each code. */
export async function runTag(args: string[], io: TagIo): Promise<number> {
  const [command, ...rest] = args;
  const parsed = parse(rest);
  const usage = (): number => {
    io.stderr(USAGE);
    return EXIT_USAGE;
  };
  if (!parsed) return usage();
  const { positional, options } = parsed;
  try {
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
      if (positional.length !== 1 || options.size > 0) return usage();
      io.stdout(briefFor(parseCandidates(await io.readFile(positional[0]))));
      return EXIT_OK;
    }
    if (command === "check" || command === "sheet") {
      if (positional.length !== 2 || options.size > 0) return usage();
      const input = parseCandidates(await io.readFile(positional[0]));
      const drafts = parseDrafts(await io.readFile(positional[1]));
      const verdicts = checkDrafts(input, drafts);
      if (command === "check") {
        return printVerdicts(verdicts, io) ? EXIT_REJECTED : EXIT_OK;
      }
      if (verdicts.some(isRefusal)) {
        printVerdicts(verdicts, io);
        return EXIT_REJECTED;
      }
      io.stdout(sheetFor(input, drafts));
      return EXIT_OK;
    }
  } catch (error) {
    if (error instanceof TagUsageError) {
      io.stderr(`${error.message}\n`);
      return usage();
    }
    io.stderr(`${error instanceof Error ? error.message : "Failed."}\n`);
    return EXIT_INPUT;
  }
  return usage();
}

/** A single synchronous write may take only part of a large output; keep writing until all of it is out. */
export function writeAllSync(
  write: (chunk: Uint8Array) => number,
  bytes: Uint8Array,
): void {
  let offset = 0;
  while (offset < bytes.length) {
    offset += write(bytes.subarray(offset));
  }
}

if (import.meta.main) {
  const encoder = new TextEncoder();
  Deno.exit(
    await runTag(Deno.args, {
      readFile: (path) => Deno.readTextFile(path),
      stdout: (text) =>
        writeAllSync(
          (chunk) => Deno.stdout.writeSync(chunk),
          encoder.encode(text),
        ),
      stderr: (text) =>
        writeAllSync(
          (chunk) => Deno.stderr.writeSync(chunk),
          encoder.encode(text),
        ),
    }),
  );
}

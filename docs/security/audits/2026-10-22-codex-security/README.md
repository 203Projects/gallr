# gallr: Codex Security audit archive — October 22, 2026

> **Historical evidence, not executable remediation instructions or a current-branch security verdict.**
> This audit describes `main` at `b822ebe15e01946282a225a8c60944389429bb64`. It does not assert that findings remain present on this PR's `develop` base (`9859484611ced2d49370cecddb74033765031d40`), later commits, or the deployed service. Revalidate each finding before implementation; never restore retired endpoints or reproduce findings against production.

## Scope and outcome

- Tool: OpenAI Codex Security CLI `0.5.1`, Standard whole-repository scan, no patch mode.
- Scan ID: `8fc559f0-7a1c-4ff8-8503-b99567394a76`.
- Audited revision: `b822ebe15e01946282a225a8c60944389429bb64` (remote main verified before and after the original scan).
- Scanner result: **14 findings — 1 high, 9 medium, 4 low**.
- These are original scanner classifications, not independently proven live vulnerabilities. Read triage before severity assignment.
- This PR archives reports only; no application code, migration, dependency, workflow, or production setting changes are included.

Independent triage prioritizes OAuth session replacement and map JSON-island XSS, then bookmark/push lifecycle isolation. The scanner-high PATH/interpreter finding requires an attacker-influenced operator execution environment and is not an established remote high. Only five selected findings received independent triage.

## Reading order

1. [Independent triage](independent-triage.md): validated paths, severity qualifications, counterevidence and limits.
2. [Complete scanner report](report.md): all findings and evidence from the pinned revision.
3. [Threat model](threatmodel.md): assumptions and trust boundaries.
4. [Findings JSON](findings.json), [coverage](coverage.json), and [scan manifest](scan-manifest.json): canonical machine-readable evidence.
5. [Artifact checksums](SHA256SUMS): integrity of the six preserved original report artifacts.

## Verification and limitations

Canonical report artifacts are copied byte-for-byte; findings/coverage hashes match the original scan manifest. Scanner counts were parsed, not manually inferred. Original isolated reproducer checks were rerun during audit triage; their exact scope is recorded in the triage document. Reproducer source and execution evidence remain in the separately delivered audit bundle; this documentation-only PR does not add executable probes or claim a fresh application test run.

Whole-repository scope is not exhaustive file review. No production/provider inspection, live credential access, complete application suite, or full migration replay was performed. Deployment reachability and external protections remain unverified. Original artifact text includes audit-machine paths for provenance; these are historical references, not portable commands or required local paths.

Before remediation, compare the audited SHA with the current target branch and check for fixes already merged. Preserve immutable audit evidence; record later verification or corrections separately. Keep this security material within the private repository and review disclosure before any visibility change.

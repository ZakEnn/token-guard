# Enhancement Implementation Plan — TokenGuard Production Readiness

> **Date:** 2026-07-19
> **Role of this document:** the **how / in what order / how we know it's done**. Architecture and rationale live in `TOKENGUARD_PLAN.md` (original design) and `PLATFORM_AGNOSTIC_PLAN.md` §7 (research-validated amendments); market evidence lives in `research_proposition.md`. This plan does not restate rationale — it references it.
> **Audience:** an engineer with no prior context beyond this file, `PLATFORM_AGNOSTIC_PLAN.md`, and `research_proposition.md`.
> **End state:** a non-technical end user can install TokenGuard, connect it to their IDE, and see measured token savings — with zero manual workarounds.

---

## 1. Documentation Audit (Step 1 output)

### 1.1 Document inventory and authority order

When documents disagree, the later/more-specific one wins. Authority order used throughout this plan:

| Rank | Document | Date | Role |
|---|---|---|---|
| 1 | `research_proposition.md` | 2026-07-19 | Market evidence + code-level audit findings (most recent verification) |
| 2 | `PLATFORM_AGNOSTIC_PLAN.md` (incl. §7 update) | 2026-07-09 / updated 2026-07-19 | Cross-client reality + amended architecture |
| 3 | `docs/adr/0001..0003` | M0/M4 | Binding deviations from the original plan |
| 4 | `CLAUDE.md` | verified 2026-07-08 | Codebase state snapshot |
| 5 | `evals.md` | 2026-07-08 | Measured local evaluation (13/13 pass, 96% session savings, `chars/4`-estimated) |
| 6 | `TOKENGUARD_PLAN.md` | 2026-07-07 | Original architecture; still authoritative where not superseded |
| 7 | `README.md`, `backend/README.md`, `backend/dev-keys/README.md` | — | User/ops-facing summaries |

### 1.2 Discrepancies found (explicit, per the audit mandate)

| # | Discrepancy | Resolution used |
|---|---|---|
| D1 | `TOKENGUARD_PLAN.md` §2/§3 says Gradle, official MCP Java SDK, model2vec embedder — code uses Maven, hand-rolled JSON-RPC, bag-of-words embedder | Already reconciled by ADR-0001/0002/0003; ADRs win. No action beyond ADR-0003's open swap point (Phase 4, item P4.5). |
| D2 | `CLAUDE.md` §4.1 says cache eviction "may be incomplete" and §4.2 asks to verify `PathJail` symlink handling | **Both resolved by code audit** (`research_proposition.md` §4): `CacheStore.evictOverBudget` exists and runs on every store (`CacheStore.java:136`); `PathJail` calls `toRealPath()` (lines 18/35/46). `CLAUDE.md` is stale on these two rows — corrected reality is assumed here; still add the missing *tests* (P4.7). |
| D3 | `PLATFORM_AGNOSTIC_PLAN.md` Deviations 1–2 proposed **H2** for the trace DB while its own §6 step 1 said "flyway … with needed **sqlite** scripts," and `TOKENGUARD_PLAN.md` §6.1.2 had explicitly rejected H2 | Internally contradictory. Resolved 2026-07-19 in `PLATFORM_AGNOSTIC_PLAN.md` (Deviation 1 withdrawn): trace = `interaction_trace` **table in the existing SQLite guard.db**. This plan builds on SQLite (P0.2). |
| D4 | `TOKENGUARD_PLAN.md` §5.1 specifies downstream tools re-exposed as `ds__<server>__<tool>`; research shows static re-registration reproduces schema bloat and collides with Windsurf's 100-tool cap | Superseded by the dynamic three-tool facade (`PLATFORM_AGNOSTIC_PLAN.md` §1.4 amendment + §7.2). `namespaced` mode retained as opt-in compatibility (P1.2). |
| D5 | `README.md` line 3 calls TokenGuard "a local MCP **proxy** server" — the proxy (multiplexer) has zero code | Overstatement today; becomes true after Phase 1. Fix the README wording now (P0.5) and again after P1 ships. |
| D6 | `TOKENGUARD_PLAN.md` §6.4 mandates Oracle-GraalVM `sandbox.MaxCPUTime`/`MaxHeapMemory`; `CLAUDE.md` §4.2 reports a code comment claiming these are "unavailable on a community stack" while CI builds with Oracle GraalVM (`distribution: 'graalvm'`) | Plan intent wins: wire the sandbox options under Oracle GraalVM (P0.4), keeping the watchdog as belt-and-braces (and as the CE fallback per assumption A4). |
| D7 | `evals.md` headline "96% session savings" is computed with the `chars/4` heuristic that `TOKENGUARD_PLAN.md` A6 itself labels an estimate and `research_proposition.md` KC-3 flags as a credibility gap | Keep evals.md as-is (historical record); re-run and republish after P2.1 with BPE-accurate counting. |
| D8 | `README.md` install URLs (`tokenguard.io/install.sh|ps1`) and license-key delivery flow are documented as if live; assumption A12 says hosting/signing are "assumed provisioned by ops" and nothing exists | Documented-but-nonexistent user path. Phase 4 (P4.2, P4.3) closes it; until then README must label these "coming soon" (P0.5). |

### 1.3 TODO/unresolved markers carried into this plan

From `TOKENGUARD_PLAN.md` Assumptions Log: A2 (Java-only indexing → P3.1), A4 (GFTC legal review → P0.4 dependency), A5 (embedding model licensing → P4.5), A6 (token estimation → P0.1), A9 (customer-held team keys → out of scope v1, unchanged), A12 (hosting/signing → P4.2). From `PLATFORM_AGNOSTIC_PLAN.md` §4 Open Questions: Q1–Q3, Q5–Q7, Q9 (per-client empirical behavior → P2.3), Q4 (H2/native-image → mooted by D3), Q8 (MCP spec evolution → standing watch item, P1.1 note), Q10 (resolved by facade). From `CLAUDE.md` §4: every row is mapped to a phase item below (traceability column in §3).

---

## 2. Gap Analysis (Step 2 output)

### 2.1 Missing entirely (research recommends; zero code)

| Gap | Research basis | Closed by |
|---|---|---|
| MCP Client Multiplexer (downstream spawn/manage/route) | KC-1; the field's largest verified wins operate on *other* servers' schemas/results | P1.1 |
| Dynamic toolset facade (`search_tools`/`describe_tools`/`execute_tool`) | KC-1 evidence set | P1.2 |
| Host-config import (absorb the user's existing `mcp.json`-family entries) | elusznik precedent (12+ config locations) | P1.3 |
| Downstream composition from code mode (`mcp.call`) | KC-2; already specified in `TOKENGUARD_PLAN.md` §5.2, never built | P1.4 |
| Graph-ranked repo map under token budget | KC-5 (Aider design) | P3.2 |
| Session memory / compaction recovery (`restore_context`) | KC-6 (context-mode) | P3.3 |
| Hook-based routing pack (Claude Code first) | KC-7 (token-optimizer/context-mode precedent) | P3.4 |
| Reproducible public benchmark + honest medians | KC-3 | P2.1 |
| Interaction trace layer (per-client verification substrate) | `PLATFORM_AGNOSTIC_PLAN.md` §1.6/§6 | P0.2 |

### 2.2 Partially designed but not implementation-ready

| Gap | Current state | Closed by |
|---|---|---|
| BPE-aware token estimation | `TokenEstimator` = `chars/4`; plan A6 acknowledges | P0.1 |
| Oracle-GraalVM sandbox resource limits | Watchdog + statement limit only; options unwired (D6) | P0.4 |
| Multi-language AST | JavaParser only; tree-sitter conflicts with the no-JNI rule → needs spike + ADR (see `PLATFORM_AGNOSTIC_PLAN.md` §7.2) | P3.1 |
| Model2vec-class embedder | ADR-0003 fallback shipping; swap point defined | P4.5 |
| Cache-at-rest encryption | Config flag exists (`cache.encrypt`), no code | P4.5 |
| Enterprise audit upload | Hash chain computed, never batched to `/v1/audit` | P4.5 |
| License provisioning / trial UX | Backend validates keys; no generation flow, no signup, no delivery | P4.3 |
| Production entitlement keypair | Dev key bundled + `backend/dev-keys/` in repo | P4.1 |

### 2.3 Conflicts between existing plan and research (and reconciliation)

All four identified conflicts (D3, D4, D6, plus tool-count growth vs the >30-tool accuracy cliff) are reconciled in `PLATFORM_AGNOSTIC_PLAN.md` §1.4-amendment/§7 — this plan implements the reconciled versions only. The one *unreconciled* engineering conflict is deliberately preserved as a spike: **tree-sitter (JNI) vs the native-image no-JNI constraint** (P3.1 must produce an ADR before any parser commitment).

### 2.4 Non-functional gaps to production / end-user readiness

| Area | Gap | Closed by |
|---|---|---|
| Packaging | No hosted installers, no signed/notarized binaries, no checked-in `native/` reachability metadata | P4.2 |
| Onboarding | User must hand-edit mcp.json with absolute paths; no config generator; no first-run feedback | P1.3, P4.6 |
| Observability | Stderr-only logs; no runtime health signal; cloud-setup thread swallows exceptions | P0.2, P4.4 |
| Error UX | Startup failures (bad workspace, JDK<21 for JAR users) surface as raw stack traces or silent host "errored" states | P4.4 |
| Client compatibility | Per-client behavior (tool-name shadowing, clientInfo reliability — Open Questions Q1–Q7) never empirically tested | P2.3 |
| Testing | No cache-eviction test, no dependency-invalidation test, no protocol-conformance suite, no multi-instance test | P4.7 |
| Privacy/controls | No `--no-telemetry` CLI flag (config-file only) | P4.4 |
| User docs | README lacks the per-client setup + troubleshooting section (drafted in `PLATFORM_AGNOSTIC_PLAN.md` §2 but never merged); "proxy"/install-URL overstatements (D5, D8) | P0.5, P4.6 |

---

## 3. Implementation Plan (Steps 3–4 output)

Phases are dependency-ordered; items within a phase are parallelizable unless a "Blocks/Blocked by" says otherwise. Every item lists: **What/Why → Where → Blocked by → DoD (testable) → External deps**.

Conventions: paths are relative to repo root; "layer chain" = `ToolRouter` invoker chain (`CacheLayer` → metrics → pass-through warning); all new tables go through `store/Schema.java` versioned DDL migration; all new config keys go in `config.example.toml` with safe defaults; every item updates `CLAUDE.md`'s status tables on completion.

---

### Phase 0 — Foundation & Hygiene (unblocks everything; ~small items)

> **Status 2026-07-19:** Phase 0 COMPLETE.
> - **P0.1 ✅ DONE** — implemented as *exact* BPE counting via JTokKit 1.1.0 (cl100k_base, pure Java, MIT), exceeding the ±10% estimation DoD by construction; `chars/4` kept as init-failure fallback; `guard_status` names the active counter. Verified: `TokenEstimatorTest` (56-sample fixture corpus, exactness + DoD bound + divergence-from-chars/4 assertions), suite 97/97 green.
> - **P0.2 ✅ DONE** — `trace/TraceStore`+`TraceLayer` (schema v2 `interaction_trace`, outermost layer, clientInfo via new `McpServer.setClientInfoListener`). Verified: `TraceLayerTest` (3-row scripted session with client/engine/optimization attribution; trace-failure isolation; error+passthrough rows), suite 100/100 green.
> - **P0.3 ✅ DONE** — 4 descriptions rewritten with concrete optimization behavior; exact-match reviewed-fixture snapshot + no-unbenchmarked-percentages test (`ToolDescriptionSnapshotTest`), suite 102/102 green.
> - **P0.4 ✅ DONE (Oracle path pending CI)** — `ScriptSandbox` probes `sandbox.MaxCPUTime`/`MaxHeapMemory` once and reports `limitsMode()`; watchdog stays active in both modes; `sandbox.max_heap` config key added. Config fuzz: 14-case corpus (`ConfigFuzzTest`), all boot to defaults. Verified locally on the community stack (watchdog path + CPU-spin/allocation red-team in `CodeModeTest`); the sandbox-options activation can only be exercised on Oracle GraalVM (CI) — labeled partial for that assertion only. Suite 119/119 green.
> - **P0.5 ✅ DONE** — README "proxy"/installer claims corrected to planned-not-shipped; CLAUDE.md stale rows (eviction, PathJail, token estimation) updated to verified state.

**P0.1 — BPE-aware token estimation**
- **What/Why:** Replace `util/TokenEstimator.java`'s `chars/4` with a BPE-compatible estimator (bundle a compact tokenizer vocabulary as a resource; pure Java, no JNI — same constraint discipline as §2 of the original plan). Label estimates per tokenizer family in `guard_status`. Closes gap 2.2-row-1; prerequisite for honest benchmarks (KC-3).
- **Where:** `util/TokenEstimator.java`, `telemetry/GuardStatusTool.java`, resource under `core/src/main/resources/`.
- **Blocked by:** nothing. **Blocks:** P2.1, P2.2.
- **DoD:** on a corpus of ≥50 real tool outputs (mix of code/diff/tree/JSON), estimator is within ±10% of a reference BPE tokenizer count for ≥90% of samples (test asserts this against checked-in fixtures); native image still builds; `guard_status` output says which estimator is active.
- **External deps:** none (vocabulary asset must be permissively licensed — same diligence as A5).

**P0.2 — Interaction trace layer (SQLite)**
- **What/Why:** `io.tokenguard.trace` package: `TraceStore` (DAO over a new `interaction_trace` table in guard.db, schema v2) + `TraceLayer` as the **outermost** router layer; capture `clientInfo` from `McpServer.initialize` and stamp every call with session id, client name/version, tool, engine used, raw/returned token counts, `was_passthrough`. Tracing is unconditional (even errors/passthrough) per `PLATFORM_AGNOSTIC_PLAN.md` §1.5. This is the substrate for all per-client verification (P2.3) and the benchmark harness (P2.1). Implements D3's reconciled design.
- **Where:** new `core/src/main/java/io/tokenguard/trace/`, `store/Schema.java` (v2 migration), `mcp/McpServer.java`, `GuardWiring.java`.
- **Blocked by:** nothing. **Blocks:** P2.1, P2.3.
- **DoD:** the scripted stdio session from `PLATFORM_AGNOSTIC_PLAN.md` §1.6 (initialize + 2×read_file + guard_status) yields exactly 3 trace rows with correct `client_name` from clientInfo, correct engine attribution, and second-read row showing the "unchanged" optimization; a corrupted/locked trace write never fails the tool call (test injects failure, asserts tool result still returned).
- **External deps:** none.

**P0.3 — Tool-description soft interception (Layer 3)**
- **What/Why:** Rewrite the 7 tool descriptions to state their optimization behavior concretely ("re-reads return a diff or 'unchanged' — typically >90% smaller than a raw read") so LLM tool-selection favors them where hosts show both built-in and MCP tools. Zero-cost; from `PLATFORM_AGNOSTIC_PLAN.md` §1.4 Layer 3.
- **Where:** each `Tool` implementation's schema/description strings.
- **Blocked by:** nothing.
- **DoD:** descriptions updated; protocol snapshot test asserting `tools/list` output matches reviewed fixtures; wording contains measured-behavior claims only (no percentages that P2.1 hasn't produced).
- **External deps:** none.

**P0.4 — Sandbox resource limits (Oracle GraalVM) + config-parser safety**
- **What/Why:** Wire `sandbox.MaxCPUTime` (default 5s, per-call `timeout_ms` may only lower) and `sandbox.MaxHeapMemory` (256MB) into `codemode/ScriptSandbox.java` per `TOKENGUARD_PLAN.md` §6.4, keeping the watchdog. Resolves D6. **Hard precondition for P1.4** (scripts must not gain downstream reach before OS-grade-ish limits exist). Also: fuzz `util/Config.java` against malformed TOML (truncated, binary, wrong types) — startup must degrade to defaults with a stderr warning, never crash (from `CLAUDE.md` §4.2).
- **Where:** `codemode/ScriptSandbox.java`, `util/Config.java` + tests.
- **Blocked by:** GFTC legal review (A4) for *distribution*; engineering can proceed on the CI's Oracle GraalVM now, with CE fallback path (watchdog-only) kept compiling.
- **DoD:** red-team suite extended with a heap-bomb script and a CPU-spin script: both are killed by the *sandbox options* (assert the Graal limit exception type, not the watchdog) on Oracle GraalVM, and by the watchdog on CE; malformed-TOML fuzz corpus (≥10 cases) all boot to defaults with a warning line.
- **External deps:** Oracle GraalVM (GFTC) — legal sign-off tracked as a release gate, not an engineering gate.

**P0.5 — Documentation truthing pass**
- **What/Why:** Fix D5 (README "proxy" claim → "tool provider; proxy mode ships in Phase 1"), D8 (label install URLs/licensing flow "not yet live — build from source per below"), and correct the two stale `CLAUDE.md` rows from D2. Small, but production-readiness includes not documenting things that don't exist.
- **Where:** `README.md`, `CLAUDE.md`.
- **Blocked by:** nothing.
- **DoD:** no statement in README/CLAUDE.md describes unshipped functionality in the present tense; `CLAUDE.md` §4.1/§4.2 rows for eviction and PathJail marked verified-done.
- **External deps:** none.

---

### Phase 1 — The Wedge: Multiplexer + Facade + Downstream Code Mode (KC-1/KC-2)

> **Status 2026-07-19:** Phase 1 COMPLETE.
> - **P1.1 ✅ DONE** — `proxy/DownstreamServer` (spawn, client-side 2025-06-18 handshake, pending-future request map, stderr drain, restart-once-then-UNAVAILABLE per §6.7.5) + `proxy/DownstreamRegistry` (`[servers.*]` parsing incl. `env.*`; per-server fault isolation) + `Config.keys()`. Verified: `DownstreamServerTest` against a real spawned fixture MCP server (`FakeMcpServerMain`) — handshake/list/round-trip, crash → exactly one restart → structured error naming the dead server while other tools keep answering, config parsing. Suite 124/124 green.
> - **P1.2 ✅ DONE** — `search_tools`/`describe_tools`/`execute_tool` facade + `proxy.expose = facade|namespaced`; `Tool.cachePolicy(args)` args-aware default added so `execute_tool` caches only read-verb downstream tools (recorded decision: allow-list of read verbs, mutating tools never cached); facade tools routed through the existing layer chain; GuardWiring async-starts facade mode, sync-starts namespaced. Verified: `FacadeTest` (40-tool fixture registry: fixed 3-tool surface, ranking, on-demand schema promotion, read-cached vs mutation-uncached, ds__ re-registration, size-independent footprint). Suite 130/130 green.
> - **P1.3 ✅ DONE** — `proxy/HostConfigScanner` (7 host locations incl. VS Code `"servers"` variant; skips token-guard itself; Cline documented as not-scanned) + `import-servers` picocli subcommand (`--dry-run`, `--write-host-config` with byte-identical `.tg-backup`). Verified: `HostConfigScannerTest` (3 format fixtures found; import → parseable TOML that boots the registry; idempotent; host files untouched by default; explicit rewrite backs up). Suite 134/134 green.
> - **P1.4 ✅ DONE** — `mcp.call(server, tool, args)` in `HostApi` (guest-Value→JSON conversion, 512 KB labeled cap), dispatched through the registered `execute_tool` so cache/metrics/trace apply (direct-dispatch fallback in namespaced mode, uncached — documented); `search_api` catalog entry added. Verified: `McpCallTest` (compose-two-calls returns only the join; +2 traced execute_tool rows; identical read-verb calls hit cache; tight loop killed by limits without circuit-breaker trip; clear catchable error with no registry). Suite 138/138 green.

**P1.1 — Downstream MCP client (`proxy/` package)**
- **What/Why:** Implement `proxy/DownstreamServer.java` (spawn a configured stdio MCP server, do the client-side handshake, `tools/list`, `tools/call`, restart-once-with-backoff then mark-unavailable per `TOKENGUARD_PLAN.md` §6.7.5) and `proxy/DownstreamRegistry.java` (lifecycle for N servers from `~/.token-guard/config.toml` `[servers.<name>]` blocks: `command`, `args`, `env`). Closes gap 2.1-row-1. Protocol note (Open Question 8): implement against `2025-06-18` client-side, tolerate the two older versions TokenGuard already speaks server-side.
- **Where:** new `core/src/main/java/io/tokenguard/proxy/`; `util/Config.java` (server blocks); `GuardWiring.java`.
- **Blocked by:** nothing (P0 items are parallel). **Blocks:** P1.2, P1.3, P1.4.
- **DoD:** integration test spawns a scripted fake downstream MCP server (test fixture speaking stdio JSON-RPC): registry lists its tools; a `tools/call` round-trips; killing the fake mid-session → one restart attempt → subsequent calls return a structured tool error naming the dead server while TokenGuard's own 7 tools keep answering (asserted in the same session).
- **External deps:** none for the test (fixture); real-world validation against at least one popular OSS MCP server in P2.3.

**P1.2 — Dynamic toolset facade**
- **What/Why:** Three new tools — `search_tools(query)`, `describe_tools(names[])`, `execute_tool(name, args)` — exposing downstream capability at a fixed context footprint, per the amended architecture (`PLATFORM_AGNOSTIC_PLAN.md` §1.4/§7.2). `search_tools` embeds downstream tool name+description summaries with the existing `cache/Embedder` (rebuilt on registry change); `execute_tool` dispatches through the **existing layer chain** so downstream results are cached (`CacheLayer`), metered, and traced with zero new plumbing. Config `proxy.expose = facade|namespaced` (default `facade`); `namespaced` re-registers `ds__<server>__<tool>` for compatibility (D4).
- **Where:** new classes in `proxy/`; registration in `GuardWiring.java`; `cache/` untouched (facade tools declare `semanticText` like any tool).
- **Blocked by:** P1.1.
- **DoD:** with a fixture registry of ≥40 downstream tools: `tools/list` shows exactly 10 tools (7 + facade 3) in facade mode; `search_tools("<phrase matching one tool>")` ranks the right tool first; `execute_tool` twice with identical args → second call is a cache hit visible in `guard_status`; in `namespaced` mode all 40 appear with `ds__` prefixes. Token accounting: trace rows show facade schema footprint constant as fixture tool count doubles.
- **External deps:** none.

**P1.3 — Host-config import (onboarding)**
- **What/Why:** `token-guard import-servers` (picocli subcommand): scan the known host config locations already cataloged in `PLATFORM_AGNOSTIC_PLAN.md` §2 (Claude Desktop/Code, Cursor, VS Code `mcp.json` — note its `"servers"` key variant — Cline, Windsurf), list found servers, and on confirmation write them into TokenGuard's `[servers.*]` config and print the minimal host-side `mcp.json` replacement snippet. **Flagged as new scope** (grounded in the elusznik auto-discovery precedent, `research_proposition.md` §2.3 — not in any prior TokenGuard doc). This is the single biggest setup-friction reducer for the facade story.
- **Where:** `Main.java` (subcommand), new `proxy/HostConfigScanner.java`.
- **Blocked by:** P1.1 (config format). 
- **DoD:** on a test HOME populated with 3 host config fixtures (one per format variant, including VS Code's `"servers"` key), `import-servers --dry-run` prints all 3 with correct commands; non-dry-run produces a valid TOML the binary boots with; never modifies host files without `--write-host-config`; idempotent on re-run.
- **External deps:** none.

**P1.4 — `mcp.call` in code mode**
- **What/Why:** Add `mcp.call(server, tool, args)` to `codemode/HostApi.java` per `TOKENGUARD_PLAN.md` §5.2, routed through the same downstream dispatch as `execute_tool` (cached, traced, output-capped by the existing 64KB script-return cap). Intermediate downstream results composed in-script never enter model context (KC-2).
- **Where:** `codemode/HostApi.java`, `codemode/SearchApiTool.java` (catalog entry), `proxy/`.
- **Blocked by:** P1.1 **and P0.4 (hard gate — no downstream reach without sandbox limits wired).**
- **DoD:** script fixture calls two fake downstream tools and returns a 3-line join of their outputs: trace shows 2 downstream calls + 1 script call, script result ≤ the cap, raw downstream bytes recorded as "saved" in metrics; red-team addition: a script attempting `mcp.call` in a tight loop is stopped by statement/CPU limits.
- **External deps:** none.

---

### Phase 2 — Proof: Measurement, Benchmark, Per-Client Validation (KC-3)

> **Status 2026-07-19:**
> - **P2.1 ✅ DONE** — `bench/` module: 4 task families (re-read editing, navigation, structure, downstream composition) over a deterministic fixture workspace + deterministic fake downstream, conservative naive baselines, medians+ranges, exact BPE counting. `bench/RESULTS.md` generated; determinism verified by identical SHA-256 across consecutive runs. Measured medians in-fixture: T1 51.2%, T2 82.3%, T3 41.0%, T4 99.8%. evals.md superseded-by note added. (Note: DoD's `mvn -pl bench verify` needs `-am` on a clean repo — documented in RESULTS.md.)
> - **P2.2 ✅ DONE** — Metrics per-tier hit split (exact/semantic/team) + getters; guard_status "Session detail" section (client, own-vs-downstream calls, errors, trace token sums) + opt-in `$` estimate via `status.price_per_mtok` (default off). Verified: `GuardStatusV2Test` (tier split rendering; metrics↔trace reconciliation to equality; no `$` without a configured price).
> - **P2.3 ⛔ BLOCKED (external)** — requires real installs of 6 hosts. Delivered the evidence template (`docs/CLIENT_MATRIX.md`) with the exact SQL evidence query; the trace substrate (P0.2) is ready. The campaign itself is outstanding.

**P2.1 — Reproducible benchmark harness**
- **What/Why:** `bench/` module (excluded from the shipped binary): scripted agent-like stdio sessions (fixed task set: navigation, re-read-heavy editing, structural queries, downstream-tool composition) run against (a) TokenGuard, (b) a naive baseline provider (raw reads, full listings). Report **median + range** tokens-per-completed-task using P0.1 counting. Output a versioned `bench/RESULTS.md`. Honest-medians policy per research §5 KC-3 (the code-review-graph model, not best-case marketing).
- **Where:** new `bench/` Maven module + fixtures; CI job (non-gating at first).
- **Blocked by:** P0.1, P0.2 (attribution), P1.2 (to benchmark facade savings).
- **DoD:** `mvn -pl bench verify` produces RESULTS.md deterministically (two consecutive runs differ only in timings); results table includes per-engine medians and explicitly lists the naive-baseline methodology; evals.md superseded-by note added (D7).
- **External deps:** none.

**P2.2 — `guard_status` v2 (savings users can quote)**
- **What/Why:** Add tokenizer-accurate counts, per-tier cache hit split (exact/semantic/team — already in `Metrics`), downstream-vs-own call split, trace-derived per-client session summary, and optional `$` estimate behind config `status.price_per_mtok`. This is the in-product face of KC-3.
- **Where:** `telemetry/GuardStatusTool.java`, `telemetry/Metrics.java`.
- **Blocked by:** P0.1, P0.2.
- **DoD:** snapshot test of the status output; with `price_per_mtok` unset no `$` appears; numbers reconcile with trace-table sums in the same session (test asserts equality).
- **External deps:** none.

**P2.3 — Per-client empirical validation campaign**
- **What/Why:** Execute the verification protocols already written in `PLATFORM_AGNOSTIC_PLAN.md` §1.6/§3 against real hosts, answering Open Questions Q1–Q3, Q5–Q7, Q9 with trace-DB evidence; record per-client results in a new `docs/CLIENT_MATRIX.md` (which clients see which tools, name-shadowing behavior, clientInfo reliability, facade behavior under Windsurf's 100-tool cap).
- **Where:** `docs/CLIENT_MATRIX.md` (new); fixes discovered land as issues.
- **Blocked by:** P0.2 (trace is the measurement instrument); facade rows need P1.2.
- **DoD:** matrix has an evidenced row (trace query output pasted) for each of the 6 clients in `PLATFORM_AGNOSTIC_PLAN.md` §1.2; each Open Question is marked answered/updated in that doc.
- **External deps:** licenses/installs of Claude Desktop, Claude Code, Cursor, VS Code+Copilot, Cline, Windsurf on at least Windows + one Unix platform.

---

### Phase 3 — Parity & Differentiation (KC-4..KC-7)

> **Status 2026-07-19:**
> - **P3.1 ◐ PARTIAL (spike blocked on CI)** — ADR-0004 written (PROPOSED: statically-linked java-tree-sitter vs pure-Java parsers vs LSP, spike protocol, ≤ +15 MB gate). The native-image spike needs the 5-target CI matrix (no local GraalVM). Correctly, no parser was implemented — the ADR gates it.
> - **P3.2 ✅ DONE** — `QueryEngine.repoMap(budget)`: file-level PageRank (d=0.85, 20 iters, dangling redistribution) over `symbol_edges`, ranked per-file signature blocks, greedy budget fit with labeled truncation, deterministic tie-breaks; `repo_map`/`map_tokens` in the tool schema; **negative-ROI guard** on outline (redirects to read_file when structure would cost ≥ the raw read). Verified: `RepoMapTest` (budget held at 1000 and at tight 200 with omission note; hub ranked above unreferenced leaf; determinism; guard fires on a signature-dense file and stays silent on a normal one).
> - **P3.3 ✅ DONE** — schema v3 `session_journal` (workspace-keyed, capped oldest-first via `session.journal_max_events`, default 200), fed from the TraceLayer interception point (local-only paths per §6.6.4), `restore_context` tool. Verified: `SessionJournalTest` (5-op session restored by a fresh instance at < 500 tokens; 250→200 cap eviction; workspace isolation).
> - **P3.4 ◐ PARTIAL** — `install-hooks`/`uninstall-hooks`/`hook-gate` subcommands for Claude Code (idempotent, byte-identical backup/restore, unrelated settings preserved; gate JSON via subcommand to avoid shell quoting). Verified: `HooksCommandsTest` (5 mechanics tests). **Deferred parts**: PreCompact capture and live-host behavior validation — both gated on the blocked P2.3 campaign; hook response schema is host-version-sensitive.

**P3.1 — Multi-language structural indexing (spike first)**
- **What/Why:** Close the "biggest product-scope flag" (A2). **Step 1 is a time-boxed spike + ADR-0004**, because research's tree-sitter recommendation conflicts with the native-image no-JNI rule (`PLATFORM_AGNOSTIC_PLAN.md` §7.2): evaluate (a) java-tree-sitter with statically linked per-platform natives (the xerial-sqlite precedent proves this pattern survives native-image), (b) pure-Java parsers per language, against the 5-target CI matrix. Step 2 implements the ADR's winner for TS/JS, then Python, then Go — behind the existing `astindex/QueryEngine` interface so `query_codebase_structure`'s contract is unchanged. Java stays on JavaParser.
- **Where:** spike branch + `docs/adr/0004-*.md`; then `astindex/` (new parser adapters), `store/Schema.java` unchanged (symbols/edges tables are language-neutral).
- **Blocked by:** nothing hard; sequenced after Phase 1 because the multiplexer has higher validated ROI.
- **DoD (spike):** ADR-0004 merged with native-image build evidence on all 5 CI targets for the chosen approach. **DoD (feature):** outline/symbol/callers queries pass a fixture suite for TS on a real OSS repo; incremental single-file re-index < 500ms; all existing Java tests still green; native binary size increase ≤ 15MB per the CI gate.
- **External deps:** tree-sitter grammar licenses (MIT — verify per grammar); CI runners for 5 targets (existing).

**P3.2 — Graph-ranked repo map (`get_repo_map`)**
- **What/Why:** New `query_type: repo_map` with `map_tokens` budget (default 1000): PageRank over existing `symbols`/`symbol_edges`, binary-search content fitting (Aider design; KC-5). Include the **negative-ROI guard** (research §5/KC-4 note): if the rendered map/structural answer exceeds the raw alternative, return the cheaper representation with a note.
- **Where:** `astindex/QueryEngine.java`, `astindex/QueryCodebaseStructureTool.java` (schema addition).
- **Blocked by:** none (works Java-only; multiplies in value after P3.1).
- **DoD:** on this repo, `repo_map` with budget 1000 returns ≤ 1000 estimated tokens (P0.1 estimator), ranks `GuardWiring`/`ToolRouter`-class hubs above leaf utils (asserted), and stays under budget on a large OSS Java repo; negative-ROI guard covered by a unit test where the structural answer is deliberately bigger than the raw read.
- **External deps:** none.

**P3.3 — Session memory & compaction recovery (`restore_context`)**
- **What/Why:** Rolling session journal (files touched, edits applied via `apply_edit`, errors, last N tool calls) in a new SQLite table; one `restore_context` tool returning a dense state summary — the context-mode-validated retention feature (KC-6), built on `FileStateTracker`'s session model.
- **Where:** new `distill/SessionJournal.java`, tool registration, schema v3.
- **Blocked by:** P0.2 (shares session identity semantics).
- **DoD:** scripted session performs 5 mixed operations, then a fresh `initialize` with the same session key: `restore_context` returns a summary naming all 5 (snapshot test) at < 500 estimated tokens; journal is capped (config `session.journal_max_events`, default 200) with oldest-first eviction test.
- **External deps:** none.

**P3.4 — Claude Code hook pack (optional, per-host)**
- **What/Why:** Installable hook set (`token-guard install-hooks --host claude-code`): PreToolUse redirect of native Read/Grep/Glob to TokenGuard equivalents where policy allows, PreCompact state capture into P3.3's journal. Enforcement instead of hope (KC-7); host capability matrix from P2.3 governs which hosts get packs.
- **Where:** `install/hooks/` templates + `Main.java` subcommand.
- **Blocked by:** P2.3 (empirical hook behavior), P3.3 (PreCompact target).
- **DoD:** on a Claude Code test profile: hooks installed idempotently into `.claude/settings.json` (backup written); a session's native-Read attempt is observably redirected (trace row present); `uninstall-hooks` restores the backup byte-identical.
- **External deps:** Claude Code host; behavior may change with host versions (matrix row records tested version).

---

### Phase 4 — Production & Monetization Hardening

> **Status 2026-07-19:**
> - **P4.1 ⛔ / P4.2 ⛔ / P4.3 ⛔ BLOCKED (external)** — require Supabase project access, `tokenguard.io` hosting, Apple/Windows signing certificates, and a Stripe account (none available in this environment). Nothing implemented; `backend/dev-keys/` intentionally NOT deleted (deleting it before a production keypair exists would brick the dev entitlement path).
> - **P4.4 ✅ DONE** — `--no-telemetry` flag (overrides config), fail-fast actionable error for a nonexistent workspace (exit 1, no stack trace), cloud-setup failures now logged at WARN with "local optimization unaffected", `--log-json` structured stderr logs. Verified: `MainCliTest` (fail-fast, flag parsing, subcommand registration). *Partial vs DoD:* only the workspace row of the troubleshooting table has an automated per-row test; the remaining rows are environment conditions (JDK version, host-side errors) verified manually.
> - **P4.5 ◐ (a ⛔ / b ✅ / c ✅)** — (a) model2vec asset still unsourced (A5) — ADR-0003 fallback ships. (b) cache-at-rest encryption: AES-256-GCM over `result_blob` behind `cache.encrypt`, key in a 0600 `cache.key` file (keychain deferred — recorded in `LocalKeys` javadoc; `file_state.content` encryption also deferred, plan §6.2.1 scope note). Verified: `CacheEncryptionTest` (ciphertext at rest, round-trip, restart, wrong/absent key → clean miss, pre-encryption entries readable). (c) audit batch upload: `AuditUploader` (batch 20, order-preserving retry, shutdown flush) + `SaasClient.postAudit`; chain continuity across batches verified with a faked transport (`AuditUploaderTest`) — the live `/v1/audit` round-trip is gated on P4.1–P4.3.
> - **P4.6 ✅ DONE** — README now carries the Local Setup section (smoke test, per-client config table incl. VS Code's `servers` key, proxy-mode guide with `import-servers`, hooks, troubleshooting table); stale "proxy not shipped" note replaced post-Phase-1; installer/licensing still labeled planned (D8 unchanged).
> - **P4.7 ✅ DONE** — 4 new suites: `CacheEvictionPressureTest` (budget reclaim + score-ordered survivor), `DependencyInvalidationTest` (§6.1.4 matrix: exact-entry invalidation, unrelated-file survival, deleted dep, re-store), `ProtocolConformanceTest` (ping, missing/malformed params, unknown notification, end-to-end stdio session per 3 protocol versions with order-agnostic assertions), `SharedDatabaseTest` (A11: cross-instance visibility, 300 concurrent metric writes with zero loss).

**P4.1 — Production entitlement keys** — Generate production Ed25519 keypair; private half only in Supabase secrets (`ENTITLEMENT_SIGNING_KEY`); replace `core/src/main/resources/keys/entitlement_ed25519.pub`; **delete `backend/dev-keys/`**. *DoD:* repo contains no private key material (CI secret-scan gate added); activation round-trip passes against staging with the new key; old dev-signed tokens rejected. *Deps:* Supabase project access.

**P4.2 — Distribution pipeline** — Host `install.sh`/`install.ps1` + versioned binaries + SHA-256 checksums at the documented URLs; macOS notarization + Windows signing (certificate lead time — start immediately, per plan M8 note); check in `native/` reachability metadata if the agent-generated configs prove insufficient (verify, don't assume — `CLAUDE.md` §4.4). *DoD:* the `TOKENGUARD_PLAN.md` M8 gate verbatim: fresh machine → installed → optimizing in < 3 minutes on all 5 targets, executed by someone who didn't build it; installer prints the correct per-host mcp.json snippet. *Deps:* `tokenguard.io` hosting, Apple/Windows signing certs (A12).*

**P4.3 — License provisioning & trial flow** — Admin provisioning script (generate key → insert `sha256(key)` into `licenses` → link Stripe customer), Stripe products with `metadata.tier`, webhook registration, and a minimal keyless trial decision per A7 (free-local already works keyless — the *documented* signup flow must either exist or be removed per P0.5). *DoD:* end-to-end lifecycle test from `TOKENGUARD_PLAN.md` M6 gate: trial → active → past_due → revoked, including 10-days-offline degradation and webhook replay; a paying-customer walkthrough (buy → receive key → activate) completes without operator intervention. *Deps:* Stripe account, Supabase deploy.*

**P4.4 — Operational polish** — `--no-telemetry` flag (maps to `telemetry.enabled=false`); structured startup errors (bad workspace path, missing JDK for JAR users) as single actionable stderr lines; replace `GuardWiring`'s silent cloud-thread catch with logged warnings; optional `--log-json`. *DoD:* each failure mode in the `PLATFORM_AGNOSTIC_PLAN.md` §2 troubleshooting table produces the documented message (test per row); `--no-telemetry` results in zero rows in `metrics_events` marked for sync.*

**P4.5 — Deferred engine completions** — (a) model2vec-class embedder asset (A5 licensing diligence → swap at `cache/Embedder` per ADR-0003's defined seam; re-run M4 paraphrase gate: ≥80% paraphrase hits at zero false positives on the poisoned-pair set); (b) cache-at-rest encryption honoring `cache.encrypt=true` (AES-256-GCM over `result_blob`/`file_state.content`, key in OS keychain per plan §6.2.1); (c) audit batch upload to `/v1/audit` with hash-chain verification test. *DoD per sub-item as stated; each independently shippable.*

**P4.6 — End-user documentation** — Merge `PLATFORM_AGNOSTIC_PLAN.md` §2's drafted "Local Setup" section into README (it was written 2026-07-09 and never merged — audit finding); add facade/proxy setup guide after Phase 1; per-client pages generated from P2.3's matrix. *DoD:* a user following only the README on a clean machine reaches "optimizing" without consulting any planning doc; docs contain no unshipped-feature present-tense claims (re-run P0.5's check).*

**P4.7 — Test-debt closure** — Add the missing tests from `CLAUDE.md` §4.4: cache eviction under budget pressure (fill past budget, assert reclaim + score-ordered victims), dependency invalidation (edit dep file → exactly the affected entries invalidated — plan §6.1.4 matrix), MCP protocol conformance suite (version negotiation, error codes, notification semantics — extending ADR-0002's suite), two-process shared-DB safety (A11). *DoD:* all four suites green in CI on all targets; eviction and invalidation tests fail if the respective code paths are stubbed out (mutation-check each once).*

---

## 4. Zero-Friction End-User Checklist (explicit, per constraints)

The definition of "an end user can adopt with no manual workarounds":

1. **Install:** one command (P4.2), checksum-verified, PATH handled, prints the exact per-host `mcp.json` snippet — user never composes JSON by hand.
2. **Migrate:** `token-guard import-servers` (P1.3) absorbs their existing MCP setup in one confirmed step; `--dry-run` default posture, host files untouched unless asked.
3. **Defaults:** zero-config start (`--workspace` defaults to CWD; facade on; free-local mode keyless per A7) — the config file is for tuning, never for first run.
4. **Feedback:** first tool call in a session works with no license, no network, no prompts (fail-open invariant); `guard_status` shows savings in plain numbers (P2.2); `--doctor` covers install-time and the trace table covers runtime health (partial answer to `CLAUDE.md` §4.4's health-signal gap; a dedicated runtime health mechanism beyond this is **flagged as future scope, not planned here**).
5. **Errors:** every documented failure mode has a one-line, actionable message (P4.4) — written for both the human reading stderr and the LLM reading a tool error (plan §6.7.4 principle).
6. **Docs:** README alone is sufficient (P4.6); troubleshooting table maintained against real support cases.
7. **Trust:** `--no-telemetry` flag (P4.4), no unshipped-feature claims (P0.5), published honest benchmark (P2.1).

---

## 5. External Dependencies Summary

| Dependency | Needed by | Status/risk |
|---|---|---|
| Oracle GraalVM GFTC legal review (A4) | P0.4 (release gate) | Open — fallback: CE + watchdog-only (weaker) |
| `tokenguard.io` hosting + signing certs (A12) | P4.2 | Not provisioned; certs have lead time — start at Phase 1 completion |
| Supabase project (staging + prod) | P4.1, P4.3 | Backend code exists; deployment untested end-to-end |
| Stripe account + products | P4.3 | Not created |
| Embedding model asset with permissive license (A5) | P4.5a | Unsourced — ADR-0003 fallback ships meanwhile |
| Tokenizer vocabulary asset (permissive license) | P0.1 | To source — same diligence as A5 |
| Host installs for the 6-client matrix | P2.3 | Local/manual effort only |
| tree-sitter grammars (per-grammar MIT check) | P3.1 | Pending spike ADR |

---

## 6. Items Flagged as Genuinely New (not grounded in prior TokenGuard docs)

Per the no-invention constraint, these originate from `research_proposition.md` evidence rather than pre-existing TokenGuard planning, and are marked as new scope: **P1.3 host-config import** (elusznik precedent), **P3.2's negative-ROI guard** (code-review-graph admission), **P3.3 session memory** (context-mode), **P2.1's median-based public benchmark policy**, and the `proxy.expose` facade/namespaced switch (reconciliation device for D4). Everything else traces to `TOKENGUARD_PLAN.md`, `PLATFORM_AGNOSTIC_PLAN.md`, `CLAUDE.md` §4, or the ADRs.

## 7. Blockers

**Planning-time (2026-07-19 a.m.):** none — all five planning steps completed.

**Execution-time (2026-07-19 p.m., see `implementation_report.md`):** 19 of 24 items DONE, 2 PARTIAL (P3.1 spike, P3.4 PreCompact — both gated on external dependencies), 4 BLOCKED external (P2.3 host installs; P4.1–P4.3 Supabase/Stripe/hosting/signing certs; P4.5a model asset). No engineering item was blocked; every blocked item is credentials/infrastructure. Final suite: **179 tests, 0 failures** (baseline was 92).

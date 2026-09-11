# Implementation Report — enhancement_implementation_plan.md Execution

> **Date:** 2026-07-19 · **Executor:** autonomous single pass, items in plan order
> **Baseline:** 92 tests, 0 failures (pre-flight run) → **Final: 179 tests (178 core + 1 bench), 0 failures, BUILD SUCCESS.** No existing test was removed or weakened.
> **Environment:** Windows 11, Temurin JDK 21.0.7, Maven 3.9.10, no local GraalVM, no git repository present, network to Maven Central available; no Supabase/Stripe/hosting/signing credentials.

## 1. Scoreboard

| Item | Status | Verified by |
|---|---|---|
| P0.1 BPE token counting | ✅ DONE | `TokenEstimatorTest` — 56-sample fixture corpus; exceeds DoD (exact counting via JTokKit cl100k_base, not ±10% estimation) |
| P0.2 Trace layer | ✅ DONE | `TraceLayerTest` — 3-row scripted session, client/engine/optimization attribution, failure isolation |
| P0.3 Tool descriptions | ✅ DONE | `ToolDescriptionSnapshotTest` — exact-match reviewed fixtures + no-unbenchmarked-percentages rule |
| P0.4 Sandbox limits + Config fuzz | ✅ DONE (Oracle path pending CI) | `CodeModeTest` (limitsMode, CPU-spin, allocation loop), `ConfigFuzzTest` (14 malformed cases → defaults) |
| P0.5 Doc truthing | ✅ DONE | README/CLAUDE.md corrected (proxy claim, installer status, stale audit rows) |
| P1.1 Downstream MCP client | ✅ DONE | `DownstreamServerTest` — real spawned fixture server; crash → one restart → UNAVAILABLE naming the server; others unaffected |
| P1.2 Dynamic toolset facade | ✅ DONE | `FacadeTest` — 40-tool registry: fixed 3-tool surface, ranking, lazy schemas, read-cached/mutation-uncached, ds__ mode |
| P1.3 import-servers | ✅ DONE | `HostConfigScannerTest` — 3 config formats incl. VS Code `servers`; idempotent; host files untouched by default |
| P1.4 mcp.call | ✅ DONE | `McpCallTest` — compose-two returns only the join; traced via execute_tool; cache hit on repeat; loop killed by limits |
| P2.1 Benchmark harness | ✅ DONE | `bench/RESULTS.md` — deterministic (identical SHA-256 on rerun); medians: T1 51.2%, T2 82.3%, T3 41.0%, T4 99.8% |
| P2.2 guard_status v2 | ✅ DONE | `GuardStatusV2Test` — tier split, metrics↔trace equality, no `$` without configured price |
| P2.3 Per-client campaign | ⛔ BLOCKED (external) | Needs 6 real host installs. Delivered `docs/CLIENT_MATRIX.md` template + trace substrate |
| P3.1 Multi-language spike | ◐ PARTIAL | ADR-0004 (PROPOSED, options + spike protocol). Spike needs the 5-target native CI; no parser implemented (correctly, per ADR gate) |
| P3.2 repo_map + negative-ROI guard | ✅ DONE | `RepoMapTest` — budget held (1000 and 200), hub ranked first, labeled truncation, deterministic; guard redirects only when structure ≥ raw |
| P3.3 Session journal + restore_context | ✅ DONE | `SessionJournalTest` — 5-op restore by a fresh instance < 500 tokens; 250→200 cap; workspace isolation |
| P3.4 Claude Code hook pack | ◐ PARTIAL | `HooksCommandsTest` — idempotent install, byte-identical backup/restore, hook-gate JSON. Deferred: PreCompact capture + live-host validation (gated on P2.3) |
| P4.1 Prod keypair | ⛔ BLOCKED | Needs Supabase secrets access. `backend/dev-keys/` deliberately NOT deleted (would brick dev entitlements before a replacement exists) |
| P4.2 Distribution pipeline | ⛔ BLOCKED | Needs tokenguard.io hosting + Apple/Windows certs |
| P4.3 License provisioning | ⛔ BLOCKED | Needs Stripe + deployed Supabase |
| P4.4 Operational polish | ✅ DONE | `MainCliTest` — fail-fast bad workspace (exit≠0, one actionable line, verified live), `--no-telemetry`, `--log-json`, cloud-setup WARN |
| P4.5 Deferred engines | ◐ (a⛔ b✅ c✅) | (a) model asset unsourced (A5). (b) `CacheEncryptionTest` — ciphertext at rest, round-trip, wrong/absent key → clean miss. (c) `AuditUploaderTest` — cross-batch chain continuity, head-first retry; live `/v1/audit` gated on P4.1–P4.3 |
| P4.6 End-user docs | ✅ DONE | README: Local Setup, per-client table, proxy guide, troubleshooting; no unshipped present-tense claims |
| P4.7 Test debt | ✅ DONE | `CacheEvictionPressureTest`, `DependencyInvalidationTest`, `ProtocolConformanceTest`, `SharedDatabaseTest` |

**Net:** 17 DONE, 3 PARTIAL (each with the incomplete slice named), 4 BLOCKED (all on credentials/infrastructure, none on engineering).

## 2. What was verified, and how

1. **Unit/integration suite:** 92 → 179 tests; run after every item; no item was advanced past a red suite (two intermediate regressions — trace-session pollution in `McpCallTest`, response-ordering in `ProtocolConformanceTest` — were fixed before proceeding).
2. **Determinism:** `bench/RESULTS.md` byte-identical across two consecutive full runs (SHA-256 compared).
3. **Production pass (packaged JAR, real stdio session):** `--doctor` all-green; initialize→re-read ("unchanged")→`repo_map` (ranked, budgeted)→`restore_context` (knows prior reads)→`guard_status` (BPE label, client attribution, positive savings); `import-servers --dry-run` safe; bad workspace exits non-zero with a one-line actionable message.
4. **Real-bug found by the production pass:** PowerShell pipes prepend a UTF-8 BOM to the first stdin line, turning `initialize` into a parse error and silently losing client attribution. Fixed in `StdioTransport` (BOM strip on first character) + regression test. This validates the plan's insistence on a production pass beyond the unit suite.

## 3. Deviations from the plan (all recorded in-place as well)

| Deviation | Why |
|---|---|
| P0.1 implemented as **exact** BPE counting (JTokKit) instead of a ±10% estimator | A pure-Java, no-JNI tokenizer with bundled vocabulary existed; exactness supersedes the estimation DoD at lower complexity |
| `Tool.cachePolicy(ObjectNode)` args-aware default added (interface evolution) | `execute_tool` proxies arbitrary downstream tools; caching mutating tools would suppress re-execution — read-verb allow-list, everything else uncached |
| P4.5b key stored in a 0600 `cache.key` file, not the OS keychain; `file_state.content` not yet encrypted | Keychain needs platform-native bindings (violates the no-JNI rule); documented in `LocalKeys` javadoc as the recorded trade |
| `mcp.call` in namespaced mode dispatches directly (uncached) | No `execute_tool` is registered in that mode; documented tradeoff of the opt-in compatibility mode |
| P3.4 hook command routed through a `hook-gate` subcommand | Sidesteps cross-platform shell-quoting for the deny JSON |
| `mvn -pl bench verify` needs `-am` on a clean repo | Maven reactor semantics; documented in RESULTS.md |
| BOM tolerance added to StdioTransport (not in any plan item) | Bug found during the mandated production pass; smallest possible fix + test |

## 4. Outstanding (with reasons)

- **P2.3 campaign** — needs the six IDE installs; `docs/CLIENT_MATRIX.md` is ready to fill.
- **P3.1 spike** — needs the 5-target native-image CI (Oracle GraalVM); ADR-0004 defines the protocol and gates implementation.
- **P3.4 PreCompact + live hook validation** — depends on P2.3.
- **P4.1–P4.3** — Supabase, Stripe, hosting, signing certs. Sequenced last by design; no code was blocked on them.
- **P4.5a model2vec asset** — sourcing/licensing (A5) unresolved; ADR-0003 fallback remains, swap point unchanged.
- **P0.4 Oracle-path assertion** — the sandbox-options probe activates automatically on CI's Oracle GraalVM; only the CE/watchdog path could be asserted locally.
- **CI run** — the repo's GitHub workflow (5-target native build) could not be executed here; native-image compatibility of the new dependency (JTokKit, pure Java + classpath resources) is expected but unproven until CI runs.

## 5. Observed but out of scope (not touched)

- The project directory is **not a git repository** — deliverables are complete in the working tree; nothing could be committed/staged. Initialize a repo (or point at the real remote) before CI or release work.
- `Metrics` raw-vs-returned and trace-side estimates can differ slightly for tools that self-report `rawTokens` (visible in guard_status); reconciliation is exact for tools whose raw equals the estimate of their text (tested), approximate otherwise.
- `evals.md`'s functional results remain valid; its savings percentages are superseded by `bench/RESULTS.md` (noted in both files).

## 6. Files added/changed (summary)

- **New main:** `trace/` (2), `proxy/` (6), `distill/SessionJournal|RestoreContextTool`, `telemetry/AuditUploader`, `util/LocalKeys`, `ImportServersCommand`, `HooksCommands`.
- **Changed main:** `TokenEstimator` (BPE), `Metrics` (tiers/getters), `GuardStatusTool` usage, `Schema` (v2+v3), `McpServer` (clientInfo listener), `StdioTransport` (BOM), `ToolRouter`-adjacent `Tool` (args-aware policy), `CacheLayer` (policy call), `CacheStore` (encryption), `ScriptSandbox` (sandbox options + mcp binding), `HostApi` (mcp.call), `SearchApiTool` (catalog), `QueryEngine` (repo_map), `QueryCodebaseStructureTool` (schema + guard), `GuardWiring` (all wiring), `Main` (flags, fail-fast, subcommands), `SaasClient` (postAudit), `Log` (JSON), `Config` (keys()), 4 tool descriptions.
- **New modules/docs:** `bench/` (pom, FakeDownstream, BenchmarkTest, RESULTS.md), `docs/CLIENT_MATRIX.md`, `docs/adr/0004`, config.example.toml sections (`[proxy]`, `[servers.*]`, `[status]`, `sandbox.max_heap`).
- **New tests:** 18 test classes/suites added (~87 new tests).

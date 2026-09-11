# CLAUDE.md — TokenGuard Project Context

> **Last verified against codebase:** 2026-07-08

---

## 1. Project Description

**TokenGuard** is a local MCP (Model Context Protocol) server, written in Java 21 and compiled to a GraalVM native image, that reduces LLM token consumption in agentic coding workflows. It positions itself between the IDE/agent host and the workspace — **not** between the agent and the LLM API — by acting as a tool provider that returns fewer, denser tokens per tool call.

### How it works (high level)

The IDE spawns `token-guard` over stdio. Instead of the agent reading raw files, getting full directory dumps, or grepping entire codebases, TokenGuard exposes optimized tools that:
1. Return diffs instead of full files on re-reads.
2. Return structural summaries (signatures, callers, outlines) from an AST index instead of raw source.
3. Cache tool results with exact-key and semantic-similarity matching.
4. Allow the LLM to author small sandboxed scripts that fetch precisely the data slice needed.

### Deployment model

- **Local binary**: single native executable, zero JRE, speaks MCP over stdio. Data stored in `~/.token-guard/guard.db` (SQLite WAL).
- **Optional SaaS backend** (Supabase + Stripe): license activation, shared team cache (E2E encrypted), telemetry rollups, enterprise audit trail.
- **Fail-open**: if the backend is unreachable or the license expires, the binary continues working in pass-through mode (tools function, optimization disabled) — never a hard failure.

### Accuracy vs. the plan

The plan (§1.1) explicitly corrects the original "skips the LLM call" framing: TokenGuard is a **tool provider**, not a proxy that intercepts LLM requests. This is faithfully implemented. The four optimization engines are all implemented to a functional degree (see §2 for per-engine status).

---

## 2. Architecture

### 2.1 Component Breakdown (as actually implemented)

| Component | Status | Notes |
|---|---|---|
| MCP Frontend (stdio JSON-RPC 2.0) | ✅ Implemented | Hand-rolled framing (~100 lines), supports `2024-11-05`, `2025-03-26`, `2025-06-18` protocols |
| Tool Router (layers, registry) | ✅ Implemented | Functional layer chain (cache → metrics → pass-through warning) |
| Engine 1 — Semantic Cache | ✅ Implemented | Two-tier: exact key + bag-of-words embedder with cosine similarity; dependency-hash invalidation |
| Engine 2 — AST Indexer | ✅ Implemented | JavaParser with symbol solver, incremental hash-diff refresh, `.java` files only |
| Engine 3 — Code Mode | ✅ Implemented | GraalVM JS sandbox with `fs`/`index` host APIs, statement + time limits, circuit breaker |
| Engine 4 — Diff/Distill | ✅ Implemented | `read_file` (diff on re-read), `apply_edit` (returns diff), `list_directory`, `search_files` |
| Entitlement Manager | ✅ Implemented | Ed25519 token verification, 5 modes (FREE_LOCAL → PASSTHROUGH), 72h grace, background refresh |
| Telemetry Sync | ✅ Implemented | Daily rollups, 100k event buffer cap, opt-out, anonymized |
| Team Cache | ✅ Implemented | HMAC-keyed lookups, AES-256-GCM encrypted payloads, 150ms race timeout |
| SaaS Client | ✅ Implemented | `/activate`, `/team-cache`, `/telemetry` endpoints |
| Doctor (self-check) | ✅ Implemented | Verifies SQLite + JS sandbox on `--doctor` |
| **MCP Client Multiplexer** | ❌ Not implemented | Plan §1.3 describes proxying downstream MCP servers — **no code exists** for this |
| Enterprise Audit (local) | ⚠️ Stub | `AuditUploader` logs locally but does not batch-upload to `/audit` |
| Cache-at-rest encryption | ❌ Not implemented | `config.toml` has `cache.encrypt = false` option; no encryption code for local cache blobs |

### 2.2 Data Flow

```
IDE/Agent → stdin (JSON-RPC) → StdioTransport → McpServer → ToolRouter
                                                                │
                     ┌──────────┬───────────────┬───────────────┼─────────────┐
                     │ CacheLayer              Metrics layer     │  Pass-through│
                     │ (exact→semantic→team)                     │  warning     │
                     └──────────┴───────────────┴───────────────┘              │
                                                                               ▼
              ┌──────────────┬─────────────────┬──────────────────┬─────────────┐
              │ ReadFileTool │ QueryCodebase   │ ExecuteScript    │ ApplyEdit   │
              │ ListDirectory│ StructureTool   │ Tool / SearchApi │ Tool        │
              │ SearchFiles  │                 │                  │             │
              └──────────────┴─────────────────┴──────────────────┴─────────────┘
                     │                │                  │                │
                     ▼                ▼                  ▼                ▼
              FileStateTracker   WorkspaceIndexer   ScriptSandbox    DiffService
                                    + QueryEngine   (GraalVM JS)
                                                                         │
                                         ┌───────────────────────────────┘
                                         ▼
                              SQLite (~/.token-guard/guard.db)
```

### 2.3 File/Module Structure

```
token-guard/
├── pom.xml                          # Parent POM (multi-module, only 'core' module)
├── TOKENGUARD_PLAN.md               # Original architecture & implementation plan (~60KB)
├── config.example.toml              # Reference config file
├── README.md                        # Project overview
├── .github/workflows/
│   └── native-image.yml             # CI: test + native-image build for 5 targets + release
├── core/                            # ── THE SHIPPED BINARY ──
│   ├── pom.xml                      # JDK 21, dependencies: jackson, sqlite-jdbc, javaparser, picocli, graalvm-polyglot
│   └── src/
│       ├── main/java/io/tokenguard/
│       │   ├── Main.java            # Picocli entry point, CLI args, wiring
│       │   ├── GuardWiring.java     # Central engine registration & startup sequencing
│       │   ├── Doctor.java          # --doctor self-check (SQLite, JS sandbox)
│       │   ├── mcp/
│       │   │   ├── McpServer.java   # Protocol logic (initialize, tools/list, tools/call)
│       │   │   ├── StdioTransport.java # Newline-delimited JSON-RPC stdio framing
│       │   │   ├── ToolRouter.java  # Registry + layered invoker chain
│       │   │   ├── Tool.java        # Tool interface (name, schema, call, cachePolicy)
│       │   │   └── ToolResult.java  # Result record (text, isError, rawTokens)
│       │   ├── cache/               # Engine 1: Semantic Cache
│       │   │   ├── CacheLayer.java  # Read-through layer (exact → semantic → team → miss → store)
│       │   │   ├── CacheStore.java  # SQLite DAO (store, lookup, eviction by size budget)
│       │   │   ├── CacheKey.java    # Canonical SHA-256 key computation
│       │   │   ├── Embedder.java    # Bag-of-words random projection, 256-dim, int8 quantized
│       │   │   └── DependencyTracker.java # Thread-local file-hash accumulator for invalidation
│       │   ├── astindex/            # Engine 2: AST Index
│       │   │   ├── WorkspaceIndexer.java  # Incremental JavaParser indexer (hash-diff driven)
│       │   │   ├── QueryEngine.java       # Symbol/callers/callees/outline/hierarchy/search
│       │   │   └── QueryCodebaseStructureTool.java # MCP tool wrapper
│       │   ├── codemode/            # Engine 3: Code Mode
│       │   │   ├── ScriptSandbox.java     # GraalVM JS Context factory + limits + watchdog
│       │   │   ├── HostApi.java           # Read-only fs/index bindings (the only attack surface)
│       │   │   ├── ExecuteScriptTool.java # MCP tool: run a JS script
│       │   │   └── SearchApiTool.java     # MCP tool: API catalog (code-mode profile only)
│       │   ├── distill/             # Engine 4: Diff/Distill
│       │   │   ├── ReadFileTool.java      # Re-read → diff; range support; 2000-line cap
│       │   │   ├── ApplyEditTool.java     # The ONLY write path; returns unified diff
│       │   │   ├── ListDirectoryTool.java # Respects .gitignore, workspace-jailed
│       │   │   ├── SearchFilesTool.java   # Grep-like search with dependency tracking
│       │   │   ├── FileStateTracker.java  # Per-session known-content map (SQLite-backed)
│       │   │   ├── DiffService.java       # java-diff-utils unified diff generation
│       │   │   └── TextFiles.java         # File reading helpers, binary detection
│       │   ├── entitlement/         # License state machine
│       │   │   ├── EntitlementManager.java # Modes, background refresh, offline grace
│       │   │   └── Entitlement.java       # Parsed Ed25519-signed token (verify, activeAt, inGraceAt)
│       │   ├── teamcache/
│       │   │   └── TeamCache.java         # HMAC key derivation, AES-GCM decrypt/encrypt, 150ms race
│       │   ├── telemetry/
│       │   │   ├── Metrics.java           # In-memory atomic counters + SQLite event rows
│       │   │   ├── TelemetrySync.java     # Daily rollup builder + async upload
│       │   │   ├── GuardStatusTool.java   # guard_status tool (live dashboard)
│       │   │   └── AuditLog.java          # Hash-chain layer (enterprise)
│       │   ├── saas/
│       │   │   └── SaasClient.java        # HTTPS client for backend Edge Functions
│       │   ├── store/
│       │   │   ├── Database.java          # SQLite connection manager, corruption recovery, POSIX perms
│       │   │   └── Schema.java            # DDL v1 migration (all tables)
│       │   └── util/                # Shared utilities
│       │       ├── Config.java      # TOML parser (minimal)
│       │       ├── Crypto.java      # Ed25519, HMAC-SHA256, AES-256-GCM (JDK built-ins)
│       │       ├── DeviceId.java    # Stable machine fingerprint
│       │       ├── Globs.java       # Glob-to-predicate conversion
│       │       ├── Hashes.java      # SHA-256, hex encoding
│       │       ├── IgnoreRules.java # .gitignore parser/matcher
│       │       ├── Json.java        # Jackson helpers (obj, arr, str, prop, schemaObject)
│       │       ├── Log.java         # Stderr logger (debug/info/warn/error)
│       │       ├── PathJail.java    # Workspace sandbox (symlink resolution, jail enforcement)
│       │       └── TokenEstimator.java # exact BPE counting (JTokKit cl100k_base; chars/4 fallback)
│       ├── main/resources/keys/
│       │   └── entitlement_ed25519.pub  # Bundled public key (DEV — replace for prod)
│       └── test/java/io/tokenguard/     # 18 test files covering all engines
├── backend/                         # ── SUPABASE SAAS BACKEND ──
│   ├── README.md                    # Setup instructions
│   ├── dev-keys/                    # DEV-ONLY signing key (MUST delete before release)
│   ├── migrations/
│   │   ├── 0001_init.sql            # Full schema: teams, licenses, devices, team_cache, usage, audit, rate_limit
│   │   ├── 0002_rls.sql            # Row-Level Security policies
│   │   ├── 0003_rate_counter.sql   # incr_rate_counter() function
│   │   └── 0004_team_cache_helpers.sql  # bump_team_cache_hit, team_cache_bytes functions
│   └── functions/
│       ├── _shared/util.ts          # Shared: DB client, sha256, JWT minting, Ed25519 signing, rate limiter
│       ├── activate/index.ts        # POST /v1/activate (key validation, seat enforcement, JWT+token)
│       ├── entitlement/index.ts     # POST /v1/entitlement/refresh (heartbeat re-issue)
│       ├── telemetry/index.ts       # POST /v1/telemetry (daily rollup upsert)
│       ├── team-cache/index.ts      # GET/PUT /v1/team-cache/{hmac_key} (encrypted shared cache)
│       ├── audit/index.ts           # POST /v1/audit (enterprise hash-chain batches)
│       └── stripe-webhook/index.ts  # POST /v1/stripe-webhook (subscription→license status)
├── install/
│   ├── install.sh                   # One-line installer (curl|sh) for macOS/Linux
│   └── install.ps1                  # One-line installer (irm|iex) for Windows
└── docs/adr/
    ├── 0001-maven-instead-of-gradle.md
    ├── 0002-handrolled-jsonrpc-over-mcp-sdk.md
    └── 0003-fallback-embedder-for-tier2.md
```

### 2.4 Engine Implementation Status

| Engine | Status | Details |
|---|---|---|
| **Semantic Cache** | ✅ Fully functional | Exact-key (SHA-256) + Tier-2 semantic (bag-of-words embedder, 0.94 threshold). Dependency-hash invalidation. Team cache (cloud). LRU eviction by budget. |
| **AST Indexing** | ✅ Functional, Java-only | Incremental indexer, symbol/edge graph in SQLite. Queries: outline, symbol, callers, callees, dependencies, hierarchy, search. Only `.java` files. |
| **Code Mode** | ✅ Functional | GraalVM JS sandbox (per-call Context), fs.read/glob/grep/stat + index.symbol/callers/callees/outline/search. Statement limit (5M), time limit (5s), 64KB output cap. Circuit breaker on engine faults. |
| **Diff/Distill** | ✅ Functional | read_file (diff on re-read, range, 2000-line cap), apply_edit (returns diff), list_directory, search_files. FileStateTracker for session-known content. |

**Key limitation**: The plan's Tier-2 embedder was designed around a `model2vec`-class static embedding model (~8MB asset). The current implementation is a **fallback** bag-of-words random projection (ADR-0003). It works but has lower semantic recall than the planned model. The model asset (`embeddings/model.bin`) does not exist in the repo.

---

## 3. Local Setup & Testing

### 3.1 Prerequisites

- **JDK 21** (any distribution: Oracle GraalVM, Temurin, Corretto)
- **Maven** ≥ 3.9 (uses `maven-compiler-plugin` with `release=21`)
- **GraalVM 21** (only for native-image builds; regular JAR runs on any JDK 21)
- **Git** (for `.gitignore` parsing in tests)

### 3.2 Build Commands

```bash
# Run all tests (179 as of 2026-07-19: 178 core + 1 bench)
mvn test

# Package as runnable JAR (skip tests for speed)
mvn -q -DskipTests package
# Output: core/target/token-guard.jar + core/target/dependency/

# Native image (requires GraalVM with native-image)
mvn -Pnative -DskipTests package
# Output: core/target/token-guard (or token-guard.exe on Windows)
```

### 3.3 Running Locally

```bash
# From JAR (any JDK 21):
java -jar core/target/token-guard.jar --workspace /path/to/project --debug

# From native image:
./core/target/token-guard --workspace /path/to/project --debug

# Self-check:
java -jar core/target/token-guard.jar --doctor
```

### 3.4 IDE mcp.json Configuration

For **Cursor**, **Claude Desktop**, **Windsurf**, or **Cline**, add to your `mcp.json`:

```json
{
  "mcpServers": {
    "token-guard": {
      "command": "java",
      "args": ["-jar", "C:/path/to/core/target/token-guard.jar", "--workspace", "."],
      "env": {}
    }
  }
}
```

For a native binary:
```json
{
  "mcpServers": {
    "token-guard": {
      "command": "C:/path/to/token-guard.exe",
      "args": ["--workspace", "."],
      "env": {}
    }
  }
}
```

Optional flags: `--license-key YOUR_KEY`, `--profile code-mode`, `--debug`.

### 3.5 Running the Test Suite

```bash
mvn test
# Or specific engine tests:
mvn test -pl core -Dtest="io.tokenguard.cache.*"
mvn test -pl core -Dtest="io.tokenguard.astindex.*"
mvn test -pl core -Dtest="io.tokenguard.codemode.*"
mvn test -pl core -Dtest="io.tokenguard.distill.*"
```

### 3.6 Smoke Tests (Verifying Each Engine Reduces Tokens)

All tests use the stdio MCP protocol. You can pipe JSON-RPC to the binary:

**Engine 4 — Diff/Distill (read_file re-read optimization):**
```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"pom.xml"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"pom.xml"}}}' \
  | java -jar core/target/token-guard.jar --workspace . > out.jsonl
```
The first `read_file` returns full content; the **second** returns `"unchanged since you last read it."` — a massive token reduction. If you edit pom.xml between calls, it returns only the diff.

**Engine 2 — AST Index (query_codebase_structure):**
```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query_codebase_structure","arguments":{"query_type":"outline","target":"core/src/main/java/io/tokenguard/Main.java"}}}' \
  | java -jar core/target/token-guard.jar --workspace . > out.jsonl
```
Returns ~10-20 lines of structural summary (class, methods, signatures) instead of the full 93-line file.

**Engine 1 — Semantic Cache (transparent, check via guard_status):**
```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_files","arguments":{"pattern":"Main","glob":"**/*.java"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_files","arguments":{"pattern":"Main","glob":"**/*.java"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"guard_status","arguments":{}}}' \
  | java -jar core/target/token-guard.jar --workspace . > out.jsonl
```
The `guard_status` response shows "cache: 1/2 hits (50%)" proving the second identical call was served from cache.

**Engine 3 — Code Mode (execute_script):**
```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"execute_script","arguments":{"script":"fs.glob(\"**/*.java\").length"}}}' \
  | java -jar core/target/token-guard.jar --workspace . > out.jsonl
```
Returns a single number (e.g., `"65"`) instead of listing all 65 file paths — the agent gets exactly the data slice it needs.

### 3.7 Common Setup Issues

| Issue | Fix |
|---|---|
| `UnsupportedClassVersionError` | You're on JDK < 21. Install JDK 21+. |
| GraalVM polyglot JS warning about interpreter-only mode | Harmless but slow. Use Oracle GraalVM (not CE) for the JIT compiler. |
| `native-image` not found | Ensure `GRAALVM_HOME` is set and `native-image` is on PATH. |
| SQLite lock errors in tests | Tests use `@TempDir`; shouldn't happen. If it does, ensure no parallel Maven forks. |
| Tests fail on Windows due to path separators | All path comparisons use `replace('\\', '/')` — should work, but verify workspace path. |

---

## 4. Remaining Work for Production Readiness

### 4.1 Core Engines — Incomplete

| Item | Plan ref | Current state | What's needed |
|---|---|---|---|
| **Model2vec-class embedding model** | §6.1.1, A5, §2 | Fallback bag-of-words embedder only (ADR-0003); no `embeddings/model.bin` asset | Source/train a 256-dim static embedding model, export to int8-quantized binary, wire into `Embedder.java`, update inference loop from hash-project to token-table lookup |
| **MCP Client Multiplexer (proxy)** | §1.3 "MCP Client Multiplexer" | Zero code | Implement `proxy/DownstreamServer.java` + `DownstreamRegistry.java`: spawn/manage downstream MCP servers from config, route their tool calls through the cache/distill layers. This is what makes TokenGuard a true "only server in mcp.json" proxy. |
| **Multi-language AST support** | §A2 | Java only (JavaParser) | Plan flags this as the "biggest product-scope flag." Add TypeScript/Python/Go/Rust parsers or use tree-sitter for broad coverage. |
| **Cache-at-rest encryption** | §6.2.1 | `config.toml` option exists, no implementation | Implement AES-256-GCM wrapping of `result_blob` in `CacheStore.java` when `cache.encrypt=true`, using a key derived from the user's data-dir. |
| **Cache eviction (size-budget LRU)** | §6.1.3 | ✅ **Verified implemented** (2026-07-19 code audit): `CacheStore.evictOverBudget` runs on every store (`CacheStore.java:136`), evicts by value-density score. | Remaining: dedicated eviction-under-pressure test (enhancement plan P4.7). |
| **Proper token estimation** | A6 | ✅ **Done** (enhancement plan P0.1, 2026-07-19): exact BPE counting via JTokKit cl100k_base (pure Java, no JNI), `chars/4` retained only as init-failure fallback. | `guard_status` names the active estimator. |

### 4.2 Security — Unimplemented or Incomplete

| Item | Plan ref | Current state | What's needed |
|---|---|---|---|
| **Production entitlement keypair** | §6.2, §4.3 | Dev key in `backend/dev-keys/` and bundled in resources | Generate production Ed25519 keypair, store private in Supabase secrets only, replace bundled public key, delete `dev-keys/` directory |
| **Oracle GraalVM sandbox limits** | A4, §6.4 | Code comment says "Oracle-GraalVM CPU/heap sandbox options are unavailable on a community stack" | The CI uses `distribution: 'graalvm'` (Oracle GraalVM) — add `sandbox.MaxCPUTime` and `sandbox.MaxHeapMemory` options to `ScriptSandbox` if the GFTC license permits |
| **Symlink-following jail escape** | §6.4 | ✅ **Verified safe** (2026-07-19 code audit): `PathJail` calls `toRealPath()` at lines 18/35/46 before the jail check. | No action needed; covered by CodeModeTest escape suite. |
| **Secret scanning in HostApi** | §6.4 | Deny-list for `.env*`, `.pem`, `.key`, etc. | The deny list is hardcoded. Consider supporting a user-configurable deny list and/or scanning file content for high-entropy secrets before returning. |
| **TOML config parsing** | — | `Config.java` exists | Verify it handles malformed input safely (no exceptions that kill startup). |

### 4.3 SaaS Backend / Licensing / Monetization

| Item | Plan ref | Current state | What's needed |
|---|---|---|---|
| **Stripe product setup** | §5.3 | Webhook handler implemented | Actual Stripe products (personal/team/enterprise) need to be created, prices configured with `metadata.tier`, webhook endpoint registered |
| **License key generation/distribution** | §6.3 | Backend validates keys by hash, but no admin UI or self-service flow | Build a license provisioning flow (dashboard or admin script): generate keys, insert `sha256(key)` into `licenses`, link to Stripe customer |
| **Trial signup/activation UX** | §6.3.1 | Entitlement supports `trialing` status | No public-facing signup page, trial creation endpoint, or key delivery mechanism exists |
| **Supabase RLS policies** | §4.2 | `0002_rls.sql` migration exists | Verify the RLS policies are complete and correct for all tables (could not confirm content) |
| **Dashboard (Lovable)** | Plan mentions "out of scope" | Not started | User-facing dashboard for account management, usage analytics, team management |
| **Rate limiting completeness** | §6.5.1 | Implemented for `/activate` and team-cache | Verify all endpoints have appropriate limits |

### 4.4 Missing Tests / Error Handling / Observability

| Item | What's needed |
|---|---|
| **Integration test with real MCP handshake** | The CI smoke test does a basic handshake; add a comprehensive protocol conformance test suite (capabilities negotiation, error codes, edge cases) |
| **Cache eviction test** | Verify the size-budget eviction path works correctly under pressure |
| **Dependency invalidation test** | Verify that when a file changes, cached entries that depend on it are correctly invalidated |
| **Native-image reflection config** | No `native/` directory with GraalVM reachability metadata. The CI builds it — but if reflection is needed (Jackson, etc.), there should be explicit configs. May be auto-generated by the Maven plugin. |
| **Error recovery in GuardWiring** | The cloud-setup thread silently catches all exceptions. Consider structured logging for production debugging. |
| **Structured logging** | Currently stderr-only via `Log.java`. For production, consider structured JSON logs with correlation IDs. |
| **Health/readiness signal** | No mechanism for the host to verify TokenGuard is healthy without sending MCP requests. `--doctor` helps at install time but not at runtime. |
| **Graceful OOM handling** | The native image has `-J-Xmx6g` at build time but no runtime memory backpressure. Large workspaces could OOM the index. |

### 4.5 Other Production Gaps

| Item | Notes |
|---|---|
| **No `native/` directory** | Plan §3 specifies `native/` for per-platform reachability metadata. The CI may generate these automatically via the Maven plugin, but there's no checked-in config. |
| **Audit upload not implemented** | `AuditUploader.append()` only logs debug; no batch upload to `/v1/audit`. The hash chain is computed in `AuditLog` but never persisted to SQLite or shipped. |
| **Binary size gate** | CI checks < 80MB but there's no monitoring of binary size trends. |
| **Telemetry opt-out** | Config toggle exists but no `--no-telemetry` CLI flag for easy user opt-out without editing a file. |
| **Auto-update mechanism** | Plan doesn't specify one; installers fetch a fixed version. No self-update or update-check notification. |
| **Multi-workspace support** | The binary is scoped to one `--workspace`. If a user has multiple projects, they need multiple mcp.json entries. |

---

## 5. How-To-Use Spec (End User Facing)

### 5.1 Installation

**macOS / Linux:**
```bash
curl -sSf https://tokenguard.io/install.sh | sh
```

**Windows (PowerShell):**
```powershell
irm https://tokenguard.io/install.ps1 | iex
```

This downloads the correct native binary for your platform, verifies its SHA-256 checksum, and installs to `~/.local/bin` (Unix) or `%LOCALAPPDATA%\token-guard` (Windows).

### 5.2 Getting a License Key

> **Note:** The signup/trial flow is not yet publicly available. The mechanism below describes the intended flow.

1. Visit `https://tokenguard.io` and sign up.
2. Choose a plan (Free trial available).
3. A license key is emailed or shown in the dashboard.
4. Without a key, TokenGuard runs in **free-local mode** (all engines work, 100MB cache limit, no cloud features).

### 5.3 mcp.json Configuration

Add to your IDE's MCP configuration file:

```json
{
  "mcpServers": {
    "token-guard": {
      "command": "token-guard",
      "args": ["--license-key", "tg_abc123..."],
      "env": {}
    }
  }
}
```

**Where is mcp.json?**
- **Cursor**: `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` globally.
- **Claude Desktop**: `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows).
- **VS Code + Cline**: `.vscode/mcp.json` or the Cline extension settings.

**Optional arguments:**
- `--workspace /path/to/project` — defaults to current directory
- `--profile code-mode` — exposes only `execute_script` + `search_api` (2 tools instead of 7)
- `--debug` — verbose logging to stderr

### 5.4 What to Expect Once Running

When TokenGuard is active, you'll notice:

1. **Your agent still works normally** — all existing file/search operations continue. TokenGuard provides them, just more efficiently.
2. **The `guard_status` tool appears** — your agent can call it to see live stats:
   ```
   tool calls: 47
   tokens (estimated, chars/4): raw 284000 -> returned 41000 (saved ~243000, 85%)
   cache: 12/30 hits (40%)
   ```
3. **Re-reads are instant and tiny** — if your agent reads the same file twice, the second read returns "unchanged" or a minimal diff.
4. **`query_codebase_structure`** gives your agent structural knowledge of the codebase without reading every file.
5. **In code-mode profile**, the agent writes small scripts that precisely extract what's needed (e.g., "find all classes implementing Interface X and return their method signatures").

### 5.5 Tier Differences

| Feature | Free (no key) | Personal | Team | Enterprise |
|---|---|---|---|---|
| All 4 optimization engines | ✅ | ✅ | ✅ | ✅ |
| Local semantic cache | 100 MB | 2 GB | 4 GB | 8 GB |
| Shared team cache (cloud) | ❌ | ❌ | ✅ | ✅ |
| Usage analytics (dashboard) | ❌ | ✅ | ✅ | ✅ |
| Seat limit | 1 device | 1 device | Per-subscription | Per-subscription |
| Audit trail | ❌ | ❌ | ❌ | ✅ (hash-chain) |
| Offline grace period | N/A | 72 hours | 72 hours | 72 hours |
| Degradation on expiry | N/A | Pass-through (tools work, no optimization) | Same | Same |

**What changes for the user at each tier:**
- **Free → Personal**: Larger cache = more hits over longer sessions; usage dashboard shows your savings.
- **Personal → Team**: Teammate A's expensive index/analysis results are available to Teammate B without re-computation (encrypted shared cache).
- **Team → Enterprise**: Tamper-evident audit trail of all tool invocations (hashes only, no code content leaves the machine).

# TokenGuard — Architecture & Implementation Plan

**Status:** Design complete, ready for implementation. Planning document only — no code.
**Scope:** Local Java MCP proxy (GraalVM native image) + Supabase/Stripe SaaS backend, per `tokenguard-prompt.md`.
**Date:** 2026-07-07

---

## 1. Architecture Overview

### 1.1 A necessary correction to the product framing (read first)

The prompt's diagram shows TokenGuard sitting *between the agent and the LLM API*, "intercepting system prompts" and "skipping the LLM" on cache hits. **An MCP stdio server cannot do this.** In the MCP architecture, the IDE/agent host talks to the LLM API directly; MCP servers are *tool providers* the host calls when the LLM requests a tool. TokenGuard never sees the system prompt, the LLM request, or the LLM response, and it cannot short-circuit an LLM call.

What an MCP server *can* do — and where all four engines actually deliver their value — is control **what goes back into the context window as tool results**. Token consumption in agentic coding is dominated by tool output (file reads, directory listings, grep dumps) and tool schemas, not by the user's typed prompt. Every engine is therefore designed around one lever: *return fewer, denser tokens per tool call, and make fewer downstream calls*.

Concretely, the four engines become:

1. **Semantic Cache** → caches *tool results* (its own expensive operations + proxied downstream MCP tool results), not LLM answers. A repeated or near-identical tool invocation returns instantly from cache with zero recomputation, and — critically for the team tier — Developer B gets Developer A's expensive index/analysis results without rebuilding them.
2. **AST Indexer** → unchanged; returns structural summaries instead of raw files.
3. **Code Mode** → unchanged; collapses tool schemas (thousands of tokens of tool descriptions in every request) into 2 tools, and lets scripts return exact data slices.
4. **Distillation & Diffing** → unchanged; returns diffs instead of full files.

This is not a diminished product — it is the honest mechanism behind the 60–90% claim, and it is achievable. But marketing copy saying "skips the LLM call" should be revised to "eliminates redundant tool work and shrinks tool output," and the plan below is built on the corrected model. Flagged again in the Assumptions Log (A1).

### 1.2 Component diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│ IDE / Agent Host (Cursor, VS Code+Cline, Windsurf, Claude Desktop)       │
│   └─ mcp.json → spawns token-guard binary, speaks MCP over stdio         │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ JSON-RPC 2.0 over stdio
┌───────────────▼──────────────────────────────────────────────────────────┐
│ TOKEN-GUARD NATIVE BINARY (single process, GraalVM native image)         │
│                                                                          │
│  ┌────────────────────┐   ┌───────────────────────────────────────────┐  │
│  │ MCP Frontend       │   │ Tool Router / Profile Manager             │  │
│  │ (stdio transport,  │──▶│ (which tools are exposed; standard vs     │  │
│  │  JSON-RPC framing) │   │  code-mode profile)                       │  │
│  └────────────────────┘   └──────┬────────────────────────────────────┘  │
│                                  │                                       │
│   ┌──────────────┬───────────────┼────────────────┬───────────────────┐  │
│   ▼              ▼               ▼                ▼                   ▼  │
│ ┌──────────┐ ┌───────────┐ ┌────────────┐ ┌─────────────┐ ┌───────────┐ │
│ │ Engine 1 │ │ Engine 2  │ │ Engine 3   │ │ Engine 4    │ │ MCP Client│ │
│ │ Semantic │ │ AST Index │ │ Code Mode  │ │ Diff/Distill│ │ Multiplexr│ │
│ │ Cache    │ │(JavaParser│ │(GraalJS    │ │(file-state  │ │(proxies   │ │
│ │(2-tier)  │ │ graph)    │ │ sandbox)   │ │ tracker)    │ │ downstream│ │
│ └────┬─────┘ └─────┬─────┘ └─────┬──────┘ └──────┬──────┘ │ MCP srvrs)│ │
│      │             │             │               │        └─────┬─────┘ │
│   ┌──▼─────────────▼─────────────▼───────────────▼──────────────▼────┐  │
│   │ Storage Layer — single SQLite file (~/.token-guard/guard.db)     │  │
│   │ cache | embeddings | ast symbols/edges | file state | metrics    │  │
│   └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────────────┐  │
│  │ Entitlement Mgr  │  │ Telemetry Buffer │  │ Team Cache Sync       │  │
│  │ (signed offline  │  │ (batched, anon,  │  │ (HMAC keys, AES-GCM   │  │
│  │  token + grace)  │  │  opt-out)        │  │  encrypted payloads)  │  │
│  └────────┬─────────┘  └────────┬─────────┘  └──────────┬────────────┘  │
└───────────┼─────────────────────┼───────────────────────┼───────────────┘
            │  HTTPS (async, never blocks the stdio loop) │
┌───────────▼─────────────────────▼───────────────────────▼───────────────┐
│ SAAS BACKEND — Supabase                                                  │
│  Edge Functions: /activate /entitlement /telemetry /team-cache /audit   │
│  Postgres (RLS): licenses, devices, teams, team_cache, usage, audit     │
│  Stripe: subscriptions → webhook → license state                        │
│  (Dashboard: Lovable, out of scope)                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Component responsibilities

| Component | Responsibility |
|---|---|
| **MCP Frontend** | stdio JSON-RPC 2.0 framing, MCP handshake (`initialize`, `tools/list`, `tools/call`), request dispatch. Single-threaded read loop, worker pool for tool execution. Never writes non-protocol bytes to stdout (logs → stderr/file). |
| **Tool Router** | Exposes the configured tool profile (see §5.1). Wraps every tool call with: cache lookup → engine dispatch → cache store → metrics tick. |
| **Engine 1 — Semantic Cache** | Two-tier lookup (exact hash, then embedding similarity) over tool results, with dependency-hash invalidation. Local always; cloud tier for teams. |
| **Engine 2 — AST Indexer** | JavaParser-based incremental index of the workspace into a symbol/edge graph in SQLite. Serves `query_codebase_structure`. |
| **Engine 3 — Code Mode** | GraalVM JS `Context` sandbox executing LLM-authored scripts against a whitelisted host API (`fs`, `index`, `text`). Serves `search_api` / `execute_script`. |
| **Engine 4 — Diff/Distill** | Tracks last-known content per file per session; `read_file` returns distilled/ranged content, `apply_edit` returns a unified diff, re-reads of known files return diffs against session-known state. |
| **MCP Client Multiplexer** | Optionally spawns/wraps *other* MCP servers listed in TokenGuard's own config, so TokenGuard is the only server in `mcp.json` and can cache + distill their tool results too. This is what makes it a "proxy." |
| **Entitlement Manager** | License activation, signed entitlement token cache, offline grace, tier→feature flags. Fail-open to pass-through mode (prompt §3 requirement). |
| **Telemetry Buffer** | Local metrics table, batched anonymous upload, hard cap, opt-out. |
| **Team Cache Sync** | Exact-match cloud cache: HMAC-keyed lookups, client-side-encrypted payloads (§6.2). |

### 1.4 Request lifecycles (the two that matter)

**Tool call (e.g., `query_codebase_structure`):**
`tools/call` → Router → Engine 1 exact-hash lookup → (miss) → embedding lookup ≥ threshold with valid deps → (miss) → team-cache exact lookup (team tier, async-timeout 150 ms, skip on timeout) → (miss) → execute engine → store result + dep hashes + embedding → return. Every hit path re-validates dependency file hashes before returning (§6.1.4).

**Startup:** spawn → open/verify SQLite (rebuild if corrupt) → MCP handshake immediately (must be < 50 ms to first response; everything below is background) → load embedding matrix + cached entitlement → async license ping → async workspace index refresh (hash-diff, reparse changed files only).

---

## 2. Proposed Stack

Guiding constraint: **everything in the local binary must survive GraalVM native-image compilation on 5 targets** (linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64). That kills or complicates anything with JNI, dynamic classloading, or heavy reflection. Each pick below was filtered through that first.

| Layer | Choice | Alternatives rejected & why |
|---|---|---|
| Language/runtime | **Java 21, Oracle GraalVM (GFTC license)** native image | Community GraalVM CE: lacks the polyglot sandbox resource-limit options (`sandbox.MaxCPUTime`, `MaxHeapMemory`) needed for §6.4. GFTC is free for production use; flag A4. |
| App framework | **None (plain Java + picocli for args)** | Spring Boot / Quarkus / Micronaut: an MCP stdio server needs ~10 ms cold start and a small binary; even Quarkus adds MBs and build complexity for zero benefit — there's no HTTP server, no DI graph worth managing. Plain Java keeps native-image config trivially small. |
| MCP protocol | **Official MCP Java SDK (`io.modelcontextprotocol`)**, stdio transport only; fallback: hand-rolled JSON-RPC (~600 lines) if SDK's reflection/reactive deps fight native image | Hand-rolled first: rejected as default because protocol conformance (capabilities, cancellation, progress) is easy to get subtly wrong; but keep it as a planned escape hatch — validate SDK-in-native-image in M0 (Build Sequence step 0). |
| JSON | **Jackson databind** with generated native-image metadata | jackson-jr/dsl-json: fewer features, and Jackson has first-class reachability metadata in the shared metadata repo; the SDK uses it anyway. |
| Local DB | **SQLite via xerial sqlite-jdbc** (statically linked, ships GraalVM metadata) | H2: pure Java (attractive for native image) but weaker durability story under crash, no ecosystem tooling for users to inspect, and MVStore corruption recovery is worse than SQLite's. sqlite-jdbc ≥ 3.42 has proven native-image support. See §6.1.2 for the full evaluation. |
| Vector search | **In-memory brute-force cosine over a flat `float[]`**, vectors persisted as SQLite BLOBs | sqlite-vec/sqlite-vss: loadable C extensions — extension loading in a native-image-bundled static SQLite is fragile per-platform. Lucene HNSW: heavy dep, reflection surface. At ≤ 100k entries × 256 dims, brute force is < 10 ms and exact. A vector *database* is simply not needed at this scale. |
| Embeddings | **Static (model2vec-style) embeddings, 256-dim, int8-quantized (~8 MB), pure-Java inference** (tokenize → look up rows → mean-pool → normalize; ~200 lines) | ONNX Runtime + MiniLM: JNI native libs per platform inside native image = the single riskiest dependency in the whole project, 90 MB+, 10–50 ms/embed. DJL: same, heavier. Full reasoning in §6.1.1. |
| AST parsing | **JavaParser** (pure Java, with symbol-solver for cross-file resolution) | Eclipse JDT: more precise resolution but OSGi-shaped, large, reflection-heavy — a known native-image fight. JavaParser is designed as a plain library. Note: both options are **Java-only**; see A2 — this is the plan's biggest product-scope flag. |
| Diffing | **java-diff-utils** (pure Java, Myers diff, unified format) | Rolling own: no reason. |
| Script sandbox | **GraalVM JS (Graal.js/Truffle) in-process**, `UNTRUSTED`-style manual config + resource limits | Separate OS process: better isolation but 50–300 ms spawn per script, a second binary to ship per platform, and IPC plumbing. Threat model doesn't justify it (§6.4). |
| Crypto | **JDK built-ins**: AES-256-GCM, HMAC-SHA256, Ed25519 (`java.security`) | BouncyCastle: unnecessary; everything needed is in the JDK since 15+. |
| HTTP client | **`java.net.http.HttpClient`** (JDK) | OkHttp etc.: dependency for nothing. |
| Backend | **Supabase**: Postgres + RLS, Edge Functions (Deno/TS) for all API logic, Supabase Auth for dashboard humans only (binary uses its own token scheme, §6.3.4); **Stripe** subscriptions + webhooks | Constraint-fixed. Edge Functions chosen over PostgREST-direct because license signing, Stripe webhooks, and HMAC verification need server-side code, not just RLS. |
| CI | GitHub Actions matrix (5 targets), native-image build + smoke test *every PR* | Native-image breakage found late is the classic failure mode of this architecture; it must be continuous, not a "packaging phase." |

---

## 3. File / Module Structure

Single Gradle project, multi-module (Gradle over Maven: better native-image plugin ergonomics; either works).

```
token-guard/
├── settings.gradle.kts
├── core/                           # the shipped binary
│   └── src/main/java/io/tokenguard/
│       ├── Main.java               # picocli entry, wiring, shutdown hooks
│       ├── mcp/                    # frontend
│       │   ├── StdioTransport.java
│       │   ├── ToolRouter.java
│       │   └── ToolProfiles.java   # standard vs code-mode tool sets
│       ├── proxy/                  # MCP client multiplexer
│       │   ├── DownstreamServer.java
│       │   └── DownstreamRegistry.java
│       ├── cache/                  # Engine 1
│       │   ├── CacheKey.java       # canonicalization + SHA-256
│       │   ├── SemanticIndex.java  # in-memory matrix + cosine scan
│       │   ├── Embedder.java       # static-embedding inference
│       │   ├── DependencyTracker.java
│       │   ├── EvictionPolicy.java
│       │   └── CacheStore.java     # SQLite DAO
│       ├── astindex/               # Engine 2
│       │   ├── WorkspaceIndexer.java   # incremental, hash-driven
│       │   ├── SymbolGraph.java
│       │   ├── QueryEngine.java        # symbol/callers/deps/outline queries
│       │   └── Renderers.java          # compact text output formats
│       ├── codemode/               # Engine 3
│       │   ├── ScriptSandbox.java      # Context factory, limits, interrupt
│       │   ├── HostApi.java            # fs/index/text bindings (the ONLY host surface)
│       │   ├── ApiCatalog.java         # serves search_api()
│       │   └── OutputLimiter.java
│       ├── distill/                # Engine 4
│       │   ├── FileStateTracker.java   # per-session known-content map
│       │   ├── DiffService.java
│       │   └── Distiller.java          # range/outline/relevance extraction
│       ├── store/                  # shared SQLite: schema, migrations, integrity
│       ├── entitlement/            # license, signed token, grace, tiers
│       ├── telemetry/              # metrics counters, batcher, anonymizer
│       ├── teamcache/              # HMAC keys, AES-GCM, sync client
│       └── util/                   # token estimation, path canonicalization, ids
├── core/src/main/resources/
│   ├── embeddings/model.bin        # quantized static embedding table (~8 MB)
│   └── keys/entitlement_ed25519.pub
├── core/src/test/...               # unit + protocol conformance tests
├── native/                         # native-image configs, per-platform metadata,
│                                   # reachability JSONs, build scripts
├── backend/                        # Supabase project
│   ├── migrations/*.sql            # schema in §4.2, RLS policies
│   ├── functions/
│   │   ├── activate/ entitlement/ telemetry/ team-cache/ audit/
│   │   └── stripe-webhook/
│   └── tests/
├── install/                        # install.sh, install.ps1, checksums, release plumbing
└── docs/                           # ADRs — record each §6 decision as an ADR at build time
```

The `epe-rating`-style layered purity (domain/application/infrastructure) is deliberately **not** used here: this is a systems tool where the "domain" *is* caching, parsing, and diffing infrastructure. Packages are organized by engine, which matches how the team will actually work on it.

---

## 4. Data Model

### 4.1 Local — single SQLite file `~/.token-guard/guard.db` (WAL mode, `PRAGMA foreign_keys=ON`)

One file, not several: cross-engine transactions (cache entry + its dependency rows) and one integrity/rebuild path. The DB is **disposable by design** — every table can be rebuilt from the workspace or re-earned as cache; this is what makes the corruption story trivial (§6.7.2).

```sql
-- ============ Engine 1: cache ============
CREATE TABLE cache_entries (
  id            INTEGER PRIMARY KEY,
  exact_key     BLOB NOT NULL UNIQUE,      -- SHA-256 of canonicalized (tool, args, workspace_id)
  tool_name     TEXT NOT NULL,
  result_blob   BLOB NOT NULL,             -- MCP result JSON (optionally AES-GCM, §6.2.1)
  result_tokens INTEGER NOT NULL,          -- estimated tokens this result costs
  saved_tokens  INTEGER NOT NULL,          -- estimated tokens saved vs naive path
  embedding_id  INTEGER REFERENCES embeddings(id),
  created_at    INTEGER NOT NULL,          -- unix seconds
  last_hit_at   INTEGER NOT NULL,
  hit_count     INTEGER NOT NULL DEFAULT 0,
  ttl_seconds   INTEGER NOT NULL,          -- per-entry; default per tool class
  workspace_id  TEXT NOT NULL              -- hash of canonical workspace root
);
CREATE INDEX idx_cache_ws_tool ON cache_entries(workspace_id, tool_name);

CREATE TABLE cache_deps (                  -- invalidation set: files this entry depends on
  entry_id     INTEGER NOT NULL REFERENCES cache_entries(id) ON DELETE CASCADE,
  file_path    TEXT NOT NULL,              -- workspace-relative
  content_hash BLOB NOT NULL,              -- SHA-256 at capture time
  PRIMARY KEY (entry_id, file_path)
);

CREATE TABLE embeddings (
  id     INTEGER PRIMARY KEY,
  vector BLOB NOT NULL                     -- 256 × int8 (dequantized on load)
);

-- ============ Engine 2: AST index ============
CREATE TABLE indexed_files (
  id           INTEGER PRIMARY KEY,
  workspace_id TEXT NOT NULL,
  path         TEXT NOT NULL,
  content_hash BLOB NOT NULL,
  mtime        INTEGER NOT NULL,
  size         INTEGER NOT NULL,
  parse_status TEXT NOT NULL,              -- 'ok' | 'partial' | 'failed'
  parse_error  TEXT,
  UNIQUE (workspace_id, path)
);

CREATE TABLE symbols (
  id         INTEGER PRIMARY KEY,
  file_id    INTEGER NOT NULL REFERENCES indexed_files(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,                -- class|interface|enum|record|method|ctor|field|annotation
  fq_name    TEXT NOT NULL,
  signature  TEXT,                         -- rendered, e.g. "public List<Order> findByStatus(Status s)"
  start_line INTEGER NOT NULL,
  end_line   INTEGER NOT NULL,
  doc_summary TEXT                         -- first sentence of Javadoc only
);
CREATE INDEX idx_symbols_fq ON symbols(fq_name);

CREATE TABLE symbol_edges (
  from_id INTEGER NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
  to_fq   TEXT NOT NULL,                   -- FQ name (target may be external/unresolved)
  kind    TEXT NOT NULL,                   -- calls|extends|implements|imports|uses_type|overrides
  PRIMARY KEY (from_id, to_fq, kind)
);
CREATE INDEX idx_edges_to ON symbol_edges(to_fq);
-- Edges point at FQ names, not symbol ids: survives partial reindex; unresolved
-- externals (JDK, libs) are queryable without rows for them.

-- ============ Engine 4: file state ============
CREATE TABLE file_state (                  -- last content the AGENT has seen this session
  session_id   TEXT NOT NULL,
  path         TEXT NOT NULL,
  content_hash BLOB NOT NULL,
  content      BLOB NOT NULL,              -- needed to compute diffs on next read
  seen_at      INTEGER NOT NULL,
  PRIMARY KEY (session_id, path)
);

-- ============ Telemetry / entitlement ============
CREATE TABLE metrics_events (
  id      INTEGER PRIMARY KEY,
  ts      INTEGER NOT NULL,
  kind    TEXT NOT NULL,                   -- cache_hit|cache_miss|tokens_saved|tool_call|script_run|...
  tool    TEXT,
  n       INTEGER NOT NULL DEFAULT 1,
  synced  INTEGER NOT NULL DEFAULT 0
);                                         -- content-free by construction: no paths, no args, no code

CREATE TABLE kv (                          -- entitlement token, team key (wrapped), schema_version,
  k TEXT PRIMARY KEY, v BLOB               -- workspace generation counters, install_id
);
```

### 4.2 Cloud — Supabase Postgres (RLS on every table)

```sql
CREATE TABLE teams (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  stripe_customer_id text UNIQUE,
  team_cache_key_wrapped bytea,        -- team AES key, wrapped w/ platform KMS key (§6.2.3)
  hmac_secret bytea NOT NULL,          -- per-team HMAC secret for cache-key derivation
  created_at timestamptz DEFAULT now()
);

CREATE TABLE licenses (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key_hash bytea NOT NULL UNIQUE,      -- SHA-256; raw key never stored (§6.2.2)
  team_id uuid REFERENCES teams(id),   -- null for personal/free
  tier text NOT NULL CHECK (tier IN ('free','personal','team','enterprise')),
  status text NOT NULL CHECK (status IN ('trialing','active','past_due','expired','revoked')),
  stripe_subscription_id text,
  seat_limit int NOT NULL DEFAULT 1,
  trial_ends_at timestamptz,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  license_id uuid NOT NULL REFERENCES licenses(id),
  fingerprint_hash bytea NOT NULL,     -- §6.3.2
  first_seen timestamptz DEFAULT now(),
  last_seen timestamptz DEFAULT now(),
  binary_version text,
  os text,
  UNIQUE (license_id, fingerprint_hash)
);

CREATE TABLE team_cache_entries (
  team_id uuid NOT NULL REFERENCES teams(id),
  hmac_key bytea NOT NULL,             -- HMAC-SHA256(team.hmac_secret, canonical cache key)
  payload_ciphertext bytea NOT NULL,   -- AES-256-GCM under team key; server CANNOT read
  payload_nonce bytea NOT NULL,
  result_tokens int NOT NULL,          -- metadata OK in clear (sizes, not content)
  hit_count bigint NOT NULL DEFAULT 0,
  created_by_device uuid REFERENCES devices(id),
  created_at timestamptz DEFAULT now(),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (team_id, hmac_key)
);

CREATE TABLE usage_events (              -- pre-aggregated daily rollups, not raw events
  license_id uuid NOT NULL REFERENCES licenses(id),
  device_id uuid REFERENCES devices(id),
  day date NOT NULL,
  tool_calls bigint DEFAULT 0,
  cache_hits bigint DEFAULT 0,
  cache_misses bigint DEFAULT 0,
  tokens_saved_est bigint DEFAULT 0,
  scripts_run bigint DEFAULT 0,
  PRIMARY KEY (license_id, device_id, day)
);

CREATE TABLE audit_log (                 -- enterprise tier only (§6.6.3)
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  team_id uuid NOT NULL REFERENCES teams(id),
  device_id uuid REFERENCES devices(id),
  ts timestamptz NOT NULL,
  event_kind text NOT NULL,              -- tool_call|script_exec|cache_push|cache_pull|config_change
  tool text,
  args_hash bytea,                       -- hash only; never argument content
  script_hash bytea,
  prev_hash bytea NOT NULL               -- hash chain for tamper evidence
);

CREATE TABLE rate_limit_counters (       -- fixed-window counters (§6.5.1)
  scope text NOT NULL,                   -- 'license:<id>' | 'ip:<addr>' | 'device:<id>'
  endpoint text NOT NULL,
  window_start timestamptz NOT NULL,
  count int NOT NULL DEFAULT 0,
  PRIMARY KEY (scope, endpoint, window_start)
);
```

RLS: binaries authenticate with a short-lived JWT (§6.3.4) carrying `license_id`/`team_id` claims; policies restrict `team_cache_entries` and `audit_log` to matching `team_id`, `usage_events`/`devices` to matching `license_id`. Edge Functions run with service role only for `/activate` and the Stripe webhook.

### 4.3 Signed entitlement token (offline license state)

Compact JSON, Ed25519-signed by the `/entitlement` Edge Function, verified locally against the embedded public key, cached in `kv`:

```json
{ "lic": "uuid", "tier": "team", "team": "uuid", "status": "active",
  "limits": { "cache_mb": 2048, "cloud": true, "audit": true },
  "iat": 1783468800, "exp": 1784073600 }   // 7-day expiry + 72h offline grace
```

---

## 5. API Surface

### 5.1 MCP tools exposed upstream (to the IDE)

Two profiles, selected in TokenGuard's own config file (`~/.token-guard/config.toml`), because Engine 3's "2 tools" philosophy and Engines 2/4's named tools are in tension — resolved by making it a mode, defaulting to **standard** (agents handle explicit tools more reliably today; code-mode is the power setting):

**`standard` profile (default, 7 tools):**

| Tool | Params | Returns | Engine |
|---|---|---|---|
| `query_codebase_structure` | `query_type` (outline\|symbol\|callers\|callees\|dependencies\|hierarchy\|search), `target`, `depth?` | Compact structural text (signatures, edges, line refs) | 2 (+1 cache) |
| `read_file` | `path`, `start_line?`, `end_line?`, `mode?` (full\|outline\|range) | First read: distilled/ranged content. Re-read of session-known file: unified diff vs what agent last saw, or "unchanged" | 4 |
| `apply_edit` | `path`, `edits[] {old_string,new_string}` or `unified_diff` | Applied? + resulting minimal diff (never the full file) | 4 |
| `search_files` | `pattern`, `glob?`, `context_lines?`, `max_results?` | Grouped matches, hard-capped output | 4 (+1 cache) |
| `list_directory` | `path`, `depth?` | Compact tree, gitignore-aware, capped | 4 |
| `execute_script` | `script`, `timeout_ms?` | Script's return value (capped, §6.4) | 3 |
| `guard_status` | — | Cache hit rate, tokens saved, index freshness, tier, degradation state | — |

**`code-mode` profile (2 tools):** `search_api(query)` — searches the host-API catalog and returns relevant function signatures + examples (this replaces ~50 tool schemas with on-demand docs); `execute_script(script)`. Plus `guard_status`.

Proxied downstream-server tools (via the multiplexer) are re-exposed namespaced (`ds__<server>__<tool>`) in standard profile, or reachable as `mcp.call(server, tool, args)` inside scripts in code-mode profile — either way their results flow through Engine 1's cache and Engine 4's output caps.

### 5.2 Code Mode host API (inside the JS sandbox — the only capabilities scripts have)

```
fs.read(path, {startLine?, endLine?})   → string        (workspace-jailed, read-only)
fs.glob(pattern)                        → string[]
fs.grep(pattern, {glob?, maxResults?})  → {path,line,text}[]
fs.stat(path)                           → {size, mtime, hash}
index.symbol(fqName)                    → SymbolInfo
index.callers(fqName) / index.callees(fqName) → Edge[]
index.outline(path)                     → Outline
index.search(nameQuery)                 → SymbolInfo[]
text.slice/count/matchAll(...)          → pure helpers
mcp.call(server, tool, args)            → downstream proxied call (cached)
```
Deliberately absent: any write API (writes go through `apply_edit` only, so every mutation is visible to the user as a diff), network, process, environment access.

### 5.3 SaaS backend REST API (Supabase Edge Functions, all HTTPS/JSON)

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /v1/activate` | License key + device fingerprint | Validate key, register device, enforce seats → `{jwt, entitlement_token, team_key_wrapped?}` |
| `POST /v1/entitlement/refresh` | JWT | Re-issue entitlement token (the 24 h heartbeat) |
| `POST /v1/telemetry` | JWT | Batched daily rollups (upsert into `usage_events`) |
| `GET /v1/team-cache/{hmac_key}` | JWT (team claim) | Fetch encrypted entry or 404 |
| `PUT /v1/team-cache/{hmac_key}` | JWT (team claim) | Push encrypted entry (idempotent; server enforces size + count quotas) |
| `POST /v1/audit` | JWT (enterprise) | Append hash-chained audit batch |
| `POST /v1/stripe-webhook` | Stripe signature | Subscription lifecycle → license status transitions |

All local→cloud calls are **async and non-blocking** with respect to the stdio loop; a slow backend can never slow a tool call (team-cache reads race a 150 ms timeout against the local miss path).

---

## 6. Security & Design Decisions (Section 5 of the prompt, decision by decision)

### 6.1 Token Storage & Semantic Cache Strategy

**6.1.1 Embedding approach — Decision: two-tier; exact canonical hash first, then static (model2vec-class) embeddings; no transformer.**

Options considered:
1. *Transformer via ONNX Runtime (all-MiniLM-L6-v2)* — best semantic quality. Rejected: ONNX Runtime is JNI native libraries per platform inside a native image — the highest-risk dependency this project could take, for a ~90 MB payload and 10–50 ms per embed. It would dominate build complexity for all five targets.
2. *Pure lexical similarity (SimHash/MinHash over normalized tokens)* — zero model, native-friendly. Rejected as the *only* tier: misses paraphrase ("list methods of OrderService" vs "what functions does OrderService have").
3. *Static embeddings (model2vec/potion-class)* — a precomputed token→vector table; inference is tokenize, look up, mean-pool, normalize. Pure Java, ~8 MB int8-quantized at 256 dims, microseconds per embed, deterministic. Quality is below MiniLM on general STS, **but the cache use-case doesn't need general STS quality** — it needs high-precision near-duplicate detection at a high threshold.
4. *Exact hash only* — maximally safe, catches the (very common) literal-retry case, but zero paraphrase tolerance.

The governing constraint is that **a semantic-cache false positive is a wrong answer served confidently** — worse than any miss. So: Tier 1 exact SHA-256 over a canonicalized request (tool name + sorted/normalized args + workspace id; whitespace/case-normalized for text args). Tier 2 static-embedding cosine with a conservative default threshold (0.94, configurable), *and* Tier 2 hits are only served when the entry's dependency hashes still validate (6.1.4) and tool args that are semantically load-bearing (file paths, symbol names) match exactly — embeddings only bridge phrasing differences in free-text query fields, never argument identity. Ship the threshold behind config and log near-threshold misses locally to tune it with real data.

**6.1.2 Vector storage backend — Decision: SQLite (xerial, statically linked) for persistence; brute-force in-memory scan for search; explicitly *no* vector database.**

The prompt asks whether SQLite/H2 is "sufficient or if something else is needed." Evaluation: cache population is bounded by eviction policy at 50k–100k entries (free tier far less). 100k × 256-dim int8 vectors = ~25 MB resident; a scalar cosine scan over that is single-digit milliseconds. HNSW/ANN indexes (sqlite-vec, Lucene, external stores) buy speed at 10M+ vectors at the cost of approximate results and — for sqlite-vec — loadable C extensions inside a static native-image SQLite, which is per-platform pain. **Sufficient: yes, and then some.** SQLite over H2: better crash-durability track record, users can inspect it with standard tooling, and xerial ships native-image reachability metadata; H2's pure-Java nature was its only edge, and xerial's static linking + metadata neutralizes it. (If M0 validation finds xerial broken on some target, H2 is the drop-in fallback — the DAO layer is written against plain JDBC to keep that swap cheap.)

**6.1.3 Eviction & TTL — Decision: per-tool-class TTL defaults + value-density scoring under a tier-set size budget.**

Pure LRU ignores that entries differ 100× in what they're worth (a cached whole-workspace dependency analysis vs a cached `list_directory`). Pure TTL ignores demand. Policy: every entry gets `ttl_seconds` by tool class (structure queries 7 d, search results 24 h, script results 1 h default — script results are the most likely to encode stale assumptions); expiry is checked lazily on read and swept opportunistically. When the size budget (free 100 MB / personal 2 GB / team 4 GB, from the entitlement token) is exceeded, evict ascending by `score = saved_tokens × (1 + hit_count) / age_hours` — i.e., keep what saves the most, most often, most recently. Both knobs are config-overridable.

**6.1.4 Invalidation on code change — Decision: per-entry dependency hash sets, validated lazily at hit time; no file watcher.**

Options: (a) filesystem watcher invalidating eagerly — cross-platform watchers (inotify/FSEvents/Win32) are notoriously leaky, add native-image surface, and burn energy watching files nobody will ask about; (b) workspace-wide generation counter — correct but nukes the whole cache on any save, destroying the hit rate during active editing (the primary usage window!); (c) **lazy per-entry validation**: each cache write records the exact set of `(path, content_hash)` it read (engines already know their read set — the AST query knows which files backed it, scripts' `fs.read` calls are recorded by the host API). On a cache hit, re-hash those files before serving; any mismatch → delete entry, treat as miss. Re-hashing is cheap (an mtime+size fast-path skips actual hashing for untouched files) and it is *exactly* correct: an entry survives edits to unrelated files. Entries with no file dependencies (e.g., pure downstream API results) rely on TTL alone. This also gives AST-index freshness for free: the indexer runs the same hash-diff at startup and on demand.

### 6.2 Encryption & Data Protection

**6.2.1 Local cache at rest — Decision: no encryption by default; strict file permissions; opt-in app-level AES-GCM for enterprise compliance.**

Threat-model honestly: the cache lives on the developer's own machine and contains derivatives of *code that is already on that machine in plaintext*. An attacker who can read `~/.token-guard/` can read the repo next to it. Encryption at rest here defends against nothing real and costs key management + debuggability. What *is* real: (a) other local users on shared machines → directory `0700`, files `0600` (Windows: user-only ACL); (b) enterprise checkbox-compliance requirements ("no derived source data unencrypted outside the repo") → opt-in `cache.encrypt=true` encrypting `result_blob`/`file_state.content` values with an AES-256 key held in the OS keychain (Keychain/DPAPI/libsecret). SQLCipher was rejected: another native C dependency for a threat the design doesn't have by default.

**6.2.2 Cloud — Decision: TLS 1.2+ in transit (Supabase default, cert validation on, no pinning); Supabase-managed at-rest encryption for metadata; but team-cache *payloads* are client-side encrypted so the platform never holds readable code.** Key handling: raw license keys are never stored server-side — only `sha256(key)`; lookups hash the presented key. Cert pinning rejected: an ops footgun (key rotation bricks binaries in the field) against a threat (CA compromise) disproportionate to this data.

**6.2.3 License key storage locally — Decision: accept the key in `mcp.json` (it's already the documented UX), then minimize its runtime value.**

The prompt's own onboarding puts the key in `mcp.json` as a CLI arg — pretending to secure it with a keychain while it sits in a JSON file would be theater. Instead, reduce the blast radius of a leaked key: the key's only power is `/activate`; activation is seat-limited per device (§6.3.2) and keys are revocable/rotatable from the dashboard. The binary exchanges it at startup for a short-lived JWT + entitlement token, cached in `~/.token-guard/` (0600) — the key itself is never written anywhere else and never appears in logs, telemetry, or error messages. Also support `TOKEN_GUARD_LICENSE_KEY` env var and keychain lookup as *optional* alternatives for teams whose policies forbid secrets in dotfiles.

**6.2.4 Anonymized semantic hash construction — Decision: `HMAC-SHA256(team_hmac_secret, canonical_cache_key)` as the cloud key; payloads AES-256-GCM under a team key the server only stores wrapped; consequently the cloud tier is exact-match only.**

This is the decision where the prompt's implied design most needed challenging. Reasoning:
- A plain `sha256(query)` cloud key is dictionary-attackable: queries and file contents have low entropy ("how does auth work", common code idioms), so the platform (or a breach) could confirm what a team is working on by hashing guesses. HMAC with a **per-team secret** (delivered to members at activation, stored server-side only for issuing — see A9) makes keys meaningless without team membership and uncorrelatable across teams.
- Uploading **embeddings** to enable *semantic* matching in the cloud was considered and rejected: embedding inversion is a demonstrated attack class; a vector of proprietary code/query is not anonymized data, whatever the marketing says. Therefore: **semantic (fuzzy) matching stays local; the shared team cache matches exactly** (on the canonicalized key, which already normalizes whitespace/casing — so "same code block, same query" still hits across developers, which is the prompt's actual stated scenario). This trades some cloud hit rate for a defensible privacy claim; flagged in A8 as a product decision to ratify.
- Payloads (the cached results — which contain code) are encrypted client-side with the team AES key; Supabase stores ciphertext + size metadata only. A Supabase breach yields HMAC keys and ciphertext: nothing readable.

### 6.3 Authentication & Authorization Model

**6.3.1 Validation cadence & network failure — Decision: async ping at startup + 24 h heartbeat; 7-day signed entitlement token; 72 h grace beyond expiry; then pass-through-with-warning.** Startup never blocks on the network (an MCP server that hangs the IDE's handshake on a slow DNS lookup is dead on arrival). Sequence: serve immediately using the cached Ed25519-verified entitlement token; refresh in background. Fully offline: works normally for token lifetime + grace (≈10 days), then degrades exactly as the prompt specifies — raw pass-through, optimization off, a one-line warning appended to tool results (once per session, not per call — spamming every response would burn the very tokens we sell savings on). Clock tampering is not fought hard (monotonic sanity check only): the failure mode is a lapsed user keeping a *local* optimizer alive, which is acceptable leakage; cloud features die server-side regardless.

**6.3.2 Key sharing/abuse — Decision: device fingerprint + server-side seat counting; soft enforcement.** Fingerprint = `sha256(os machine-id ‖ username ‖ install_id)` — stable, no PII in the clear. `/activate` upserts the device; distinct devices seen in a sliding 30-day window beyond `seat_limit` → responses degrade from warn (grace) to `429 seat_limit` for *new* devices (existing seats keep working — punishing the legitimate buyer for a colleague borrowing a key is how you lose customers). Anomaly signals (one key, 40 devices, 6 countries) flag for manual revocation rather than auto-ban. Accepted premise: local-binary licensing is a paywall, not a security boundary — a determined cracker patches the binary; the design goal is making honesty frictionless and sharing inconvenient, and keeping everything *cloud-side* (team cache, analytics) strictly enforced where we actually control it.

**6.3.3 Team membership for shared cache — Decision: derived entirely server-side from `license → team_id`, enforced by RLS on a JWT claim; the binary never asserts its own team.** Removing a member = revoke license → next JWT refresh (≤ 24 h) loses the claim; team key rotation on offboarding is an enterprise dashboard action (re-wraps for remaining members; old cache entries age out via `expires_at` rather than mass re-encryption — simpler, bounded staleness).

**6.3.4 Binary↔SaaS auth — Decision: license key exchanged at `/activate` for a short-lived (1 h) HS256 JWT bearing `license_id/team_id/tier/device_id`; silent refresh; RLS keys off JWT claims.** Supabase Auth (email/password/OAuth) is for *humans on the dashboard*; forcing an interactive auth flow through a headless stdio binary was rejected as UX poison. The Edge Function mints JWTs with Supabase's signing secret so PostgREST/RLS accept them natively — custom scheme, zero custom middleware.

### 6.4 Script Execution Security (Code Mode)

**Decision: GraalVM JS in-process, host access off, virtualized read-only filesystem, no network, CPU/memory/output limits via sandbox options + watchdog. Subprocess isolation rejected for v1 (revisit if the threat model changes — A10).**

Threat model first, because it dictates everything: scripts are written by *the LLM the user is themselves driving*, executing on *the user's machine*, against *the user's own files*. This is not hostile multi-tenant code. The realistic adversary is a **prompt-injected or confused agent** — malicious instructions smuggled through a README or issue text — attempting exfiltration or destructive writes. The sandbox's job is to make the worst case of a hijacked script "burned some CPU and read files the agent could already read via `read_file` anyway."

- *In-process Graal.js vs subprocess:* subprocess gives OS-grade isolation (real memory caps via cgroups/JobObjects, crash isolation) but costs 50–300 ms spawn per script (scripts are the hot path — this is a latency product), a second shipped binary per platform, and IPC plumbing. Given the threat model above, in-process with a properly closed `Context` is proportionate. The decision is recorded as revisitable: if TokenGuard ever executes scripts from *other* parties (shared team scripts), flip to subprocess.
- *Context configuration:* `allowAllAccess(false)`; no host class lookup, no reflection, no native access, no process creation, no environment access; `IOAccess` bound to a **custom `FileSystem` implementation jailed to the canonicalized workspace root, read-only** (symlinks resolved *before* the jail check — the classic escape), and `.git`, `.env*`, and a configurable deny-glob excluded even for reads; **no network** (no socket capability is exposed, and no host API provides one). The only host objects are the §5.2 bindings, each of which re-validates paths independently (defense in depth: don't trust the FileSystem jail alone).
- *Resource limits:* `sandbox.MaxCPUTime=5s` (config, and per-call `timeout_ms` may only lower it), `sandbox.MaxHeapMemory=256MB`, statement-count ceiling as a second tripwire — these are the reason for choosing Oracle GraalVM over CE (§2, A4). A watchdog thread calls `Context.interrupt()` then `Context.close(true)` on overrun, independent of the Truffle limits (belt and braces — CPU limits don't cover time blocked in host calls; the watchdog does).
- *Output/exfil limits:* host API results are capped (e.g., `fs.read` ≤ 512 KB per call, script total return ≤ 64 KB, truncation always *labeled* so the agent knows to narrow, never silent). Since results go back into the LLM context, oversized returns are also a token-cost bug — the caps serve both masters.
- *Escape hardening:* no `eval` of host-composed strings, host API objects are frozen proxies exposing only declared members, engine version pinned and tracked against GraalVM security advisories, and every script run is recorded (script SHA-256 + outcome) locally — feeding the enterprise audit trail (§6.6.3).
- *Writes:* none from scripts, period. Mutations exist only via `apply_edit`, which returns a visible diff — a hijacked script cannot silently modify code.

### 6.5 Rate Limiting & Abuse Prevention

**6.5.1 Backend rate limiting — Decision: fixed-window counters in Postgres, enforced in Edge Functions; per-IP on unauthenticated endpoints, per-device/per-license on authenticated ones.** Redis/Upstash rejected: an extra vendor for counters Postgres handles fine at this traffic (`INSERT ... ON CONFLICT ... count = count+1 RETURNING` — one round trip, atomic). Fixed-window over sliding-window: the burst-at-boundary artifact is irrelevant for these budgets. Starting numbers (tunable): `/activate` 10/h/key + 30/h/IP (brute-force guard — with 128-bit random keys, hashing offline is hopeless anyway, this is noise control); `/entitlement` 4/day/device; `/telemetry` 24/day/device; team-cache GET 1200/h/device, PUT 300/h/device + 1 MB/entry + per-team storage quota (10 GB default) enforced on PUT.

**6.5.2 Tier quota enforcement — Decision: split by what each side can actually enforce.** Local limits (cache size, feature flags) ship in the *signed* entitlement token and are enforced by the binary — client-side, therefore crackable, therefore treated as paywall not security (§6.3.2 premise). Cloud limits (team-cache storage/ops, seats, telemetry volume) are enforced server-side and are authoritative.

**6.5.3 Free-tier user hitting limits — Decision: degrade, never break.** Cache full → aggressive eviction (§6.1.3 scoring), never a hard error; cloud features → absent, not erroring; `guard_status` reports "cache at limit — hit rate reduced; upgrade for X" and the CLI prints an occasional stderr nudge. A free tool that throws errors at its ceiling converts nobody; one that visibly works-but-less converts.

### 6.6 Logging & Analytics

**6.6.1 Local metrics collected:** per-tool call counts and latencies; cache hits/misses per tier (exact/semantic/team); estimated tokens: raw-result size vs returned size (tokens ≈ chars/4 heuristic — honest-labeled as an estimate, see A6) → `tokens_saved_est`; script runs/timeouts/failures; index status (files ok/partial/failed, last refresh); degradation events (offline, pass-through mode). All as content-free counters in `metrics_events`.

**6.6.2 Sync — Decision: local daily rollup, batched upload once per day (and on clean shutdown), ≤ 30 days buffered offline with oldest-first drop beyond a 10 MB cap.** Rollups, not raw events: the dashboard needs "tokens saved this week," not a per-call firehose, and rollups are structurally incapable of leaking content or activity rhythms.

**6.6.3 Enterprise audit trail — Decision: opt-in, hash-chained, hashes-only.** Local append-only log of tool calls (tool, `sha256(args)`, ts), script executions (script hash, outcome), team-cache push/pull, config changes; batched to `/audit` where `prev_hash` chaining makes truncation/tampering evident. Argument *content* is never in the audit trail — an audit system that copies proprietary code to a third-party cloud would fail the very compliance review it exists to pass. The args-hash still lets an auditor confirm "this exact known script/query ran" after the fact.

**6.6.4 Never logged, at any level, anywhere cloud-bound:** file contents, code snippets, diffs, prompts/queries, file paths or names (even hashed paths leak repo structure — paths appear in *local* debug logs only), embeddings, raw license keys, and any per-event timestamps finer than daily rollups. Telemetry is opt-out-able (`telemetry.enabled=false`) except license validation pings; the privacy page can then make a short, checkable claim.

### 6.7 Failure Modes & Resilience

Design invariant for the whole binary: **TokenGuard must never be the reason the agent fails.** Every engine failure has a defined fallback that keeps the tool call answering, and the stdio loop is isolated from every network call.

| # | Failure | Behavior |
|---|---|---|
| 6.7.1 | **SaaS unreachable** | Entitlement: cached token + grace (§6.3.1) → then pass-through + once-per-session warning. Team cache: skip silently (local tiers still serve); 150 ms race timeout means a *slow* backend is indistinguishable from a miss. Telemetry: buffer locally. Backoff: exponential w/ jitter, cap 1 h; `guard_status` surfaces "offline since …". |
| 6.7.2 | **Local cache corrupted** | On open failure or `PRAGMA integrity_check` failure (run at startup and on any SQLITE_CORRUPT error): close, rename to `guard.db.corrupt.<ts>` (keep one for support), create fresh, log to stderr, continue serving — first requests are cache-misses, nothing else. The DB is disposable by design (§4.1); recovery is deletion. The one non-disposable item (entitlement token) is additionally mirrored in a tiny separate file so cache corruption can't trigger licensing degradation. |
| 6.7.3 | **AST parse failure on a file** | Per-file, never per-index: mark `parse_status=failed` (or `partial` — JavaParser yields partial ASTs on recoverable errors; index what parsed), continue the batch. Structure queries touching that file return what's known + an explicit note steering the agent to `read_file` (ranged) for it. `guard_status` reports failed-file count. A source file that breaks the parser is *expected* input (mid-edit code!), not an exceptional condition. |
| 6.7.4 | **Script timeout / crash / sandbox trip** | Limits fire → interrupt + force-close the Context (each script gets a fresh Context; nothing shared to corrupt) → structured MCP *tool* error: `"Script exceeded 5s CPU limit. Partial cause: <last op>. Narrow the query or raise timeout_ms."` — an error message written for the LLM to act on, because the LLM is the caller who'll retry. Engine failures are tool-result errors, never JSON-RPC protocol errors (protocol errors make some hosts drop the server). Circuit breaker: ≥ 3 sandbox *engine* faults (not script bugs) in 5 min → code-mode disabled for the session, standard tools keep working, `guard_status` says why. |
| 6.7.5 | **Downstream MCP server dies** (multiplexer) | Restart once with backoff; then mark unavailable and return tool errors naming the dead server; TokenGuard's own tools unaffected. |
| 6.7.6 | **Binary crash** | stdio death is visible to the IDE (standard MCP restart semantics). WAL-mode SQLite makes mid-write crashes recoverable; `file_state` is session-scoped so a restart just resets diff baselines (first re-read returns full distilled content again — correct, merely less optimal). |

---

## 7. Build Sequence

Ordered to front-load the two existential risks: **(R1)** native-image compatibility of the exact dependency set, and **(R2)** whether the optimization engines actually save tokens in real IDE sessions. Everything monetization-related comes after the thing being monetized demonstrably works. Each milestone has an acceptance gate; hand milestones to an engineer/agent one at a time.

**M0 — Walking skeleton & risk burn-down (do not skip, do not shorten)**
1. Gradle multi-module scaffold; MCP Java SDK + stdio echo server with one trivial tool.
2. Wire xerial SQLite + Jackson + a hello-world Graal.js `Context` into that binary.
3. GitHub Actions matrix building the **native image on all 5 targets**, running an MCP-handshake smoke test against the produced binary.
   *Gate:* native binary < 80 MB, cold start < 50 ms, handshake green on all targets. **If the MCP SDK or sqlite-jdbc fails native-image here, swap to the documented fallbacks (hand-rolled JSON-RPC / H2) now, while it costs a day, not in month three.*

**M1 — Engine 4 (Diff/Distill) + baseline tools** — smallest engine, immediately demo-able value.
4. `read_file` (ranged/full), `list_directory`, `search_files` with output caps; path canonicalization + workspace jail (shared with the sandbox later).
5. `FileStateTracker` + `DiffService`: re-read → diff; `apply_edit` → minimal diff response.
6. Token-estimate instrumentation from day one (raw vs returned sizes) — this is R2's measuring stick.
   *Gate:* manual session in Claude Desktop + Cursor against a real repo; `guard_status` shows plausible savings on a scripted 20-step agent task.

**M2 — Engine 1, Tier 1 (exact cache)**
7. Schema §4.1; canonical cache keys; `CacheStore` read-through wrapper in the Router; dependency capture + lazy hash validation; eviction scoring + TTL classes.
   *Gate:* repeated tool calls hit; editing a dependency file invalidates exactly the right entries (test matrix for §6.1.4).

**M3 — Engine 2 (AST index)**
8. Incremental `WorkspaceIndexer` (hash-diff driven), JavaParser + symbol-solver, graph tables, `partial/failed` handling per §6.7.3.
9. `query_codebase_structure` query types + compact renderers; cache integration (deps = source files backing the answer).
   *Gate:* index a large OSS Java repo (e.g., Spring Framework) — full index < 60 s, incremental single-file update < 200 ms, outline answers ≥ 90% smaller than raw-file equivalents.

**M4 — Engine 1, Tier 2 (semantic)**
10. Embedding table asset pipeline (quantize → bundle), pure-Java `Embedder`, in-memory `SemanticIndex`, threshold + arg-identity guards per §6.1.1.
    *Gate:* paraphrase test set hits ≥ 80% at zero false positives on a poisoned-pair set (queries that look similar but must not match).

**M5 — Engine 3 (Code Mode)**
11. `ScriptSandbox` per §6.4 (jailed FileSystem, limits, watchdog, fresh Context per run); `HostApi` bindings; `OutputLimiter`; `execute_script` + `search_api` + the `code-mode` profile; escape-attempt test suite (symlink jail-break, `Java.type`, busy-loop, heap bomb, oversized returns).
    *Gate:* red-team script suite fully contained; a realistic "find all callers of X and show their bodies" script beats the standard-profile equivalent on tokens by ≥ 60%.

**M6 — SaaS backend + licensing**
12. Supabase migrations + RLS; Edge Functions `/activate`, `/entitlement`, `/stripe-webhook`; Stripe products for the three tiers; rate-limit counters.
13. Binary: `EntitlementManager` (async startup ping, token cache + Ed25519 verify, grace, pass-through degradation + once-per-session warning), device fingerprint.
    *Gate:* full lifecycle test — trial → active → expired → revoked — including 10-days-offline behavior and Stripe webhook replay.

**M7 — Telemetry + Team cache + audit**
14. Rollups + `/telemetry`; opt-out; buffered-offline cap.
15. Team cache: HMAC key derivation, AES-GCM payloads, wrapped-key delivery at activation, GET/PUT with the 150 ms race, server quotas; key-rotation flow.
16. Enterprise audit chain (local log → `/audit`).
    *Gate:* two devices on one team demonstrably share a cache entry; Supabase-side inspection shows only ciphertext + hashes; privacy checklist §6.6.4 audited against actual wire captures.

**M8 — Distribution & hardening**
17. `install.sh`/`install.ps1` (platform detect, checksum verify, PATH hint, `mcp.json` snippet printout), signed/notarized binaries (macOS notarization, Windows signing — lead time: start certs in M6), release automation.
18. Soak: 8-hour agent sessions, cache-corruption injection, downstream-server kill tests, memory ceiling under sustained code-mode load; docs; `--doctor` self-check command.
    *Gate:* fresh-machine install→optimizing in < 3 minutes on all 5 targets by someone who didn't build it.

Deliberate sequencing choices: licensing *after* engines (monetizing before R2 is proven is building the tollbooth before the road); native image *continuous from M0* (the classic failure is developing on JVM for months, then discovering the porting project); Engine 4 before Engine 2 (value per unit effort, and it exercises the whole pipeline early).

---

## 8. Assumptions Log

Every point where this plan fills a gap or contradicts the prompt, flagged for ratification:

- **A1 — MCP position (contradicts prompt §1):** An MCP stdio server cannot intercept system prompts or LLM calls; "skip the LLM" is not implementable from this seat. The plan reinterprets Engine 1 as a *tool-result* cache and all savings as tool-output/tool-schema reduction (§1.1). If literal LLM-response caching is a hard product requirement, TokenGuard must *additionally* become an LLM API proxy (OpenAI/Anthropic-compatible endpoint the IDE points at) — a materially different second product surface, out of this plan's scope.
- **A2 — Java-only structural indexing (constraint side effect):** JavaParser/JDT parse Java only, but the target users (Cursor/Windsurf/Cline users) overwhelmingly work in TS/Python/Go. v1 Engine 2 is Java-only per the constraint; Engines 1/3/4 are language-agnostic and carry the value elsewhere. Assumed acceptable for v1 with tree-sitter-class multi-language indexing as the top roadmap item. **This is the plan's biggest product-risk flag.**
- **A3 — Scale envelope:** local cache ≤ ~100k entries, workspaces ≤ ~50k source files, teams ≤ ~200 devices. The no-ANN, brute-force-scan and Postgres-rate-limit decisions are sized to this; they have headroom (~10×) but revisit if the envelope changes.
- **A4 — Oracle GraalVM under GFTC:** chosen over CE specifically for polyglot sandbox resource limits (§6.4). Assumes GFTC redistribution terms suit commercial distribution — **needs legal review**; fallback is CE + watchdog-only enforcement (weaker memory caps, subprocess isolation becomes more attractive).
- **A5 — Embedding model licensing:** assumes a permissively-licensed (MIT/Apache) static embedding model can be bundled (~8 MB), or distilled in-house from a permissive teacher. Verify before M4.
- **A6 — Token counting is estimation:** the binary never sees provider tokenizers or actual usage; "tokens saved" is chars/4-based and labeled as an estimate everywhere, including marketing-facing dashboards. Assumed acceptable.
- **A7 — Trial UX:** prompt says trial requires account+key. Assumed no keyless/anonymous mode exists (free tier also requires signup → key); if a zero-signup trial is wanted later, ship a `--no-license` local-only mode — the entitlement design already supports it as a tier.
- **A8 — Team cache is exact-match only** (consequence of the privacy stance in §6.2.4): semantic matching stays local because uploading embeddings de-anonymizes. Trades cloud hit rate for a defensible "your code never leaves your machine readable" claim. Product must ratify this trade.
- **A9 — Team key custody:** v1 wraps team keys with a platform KMS key (Supabase Vault/secret), meaning the *platform* could technically decrypt if it colluded with its own database — the claim is "Supabase breach yields nothing readable," not full zero-knowledge. True customer-held-key E2E (enterprise ask) is a roadmap item; the schema (`team_cache_key_wrapped`) already accommodates it.
- **A10 — Sandbox threat model** (§6.4): scripts originate only from the user's own agent; in-process isolation is proportionate. If shared/team-distributed scripts ever ship, the recorded decision is to move execution to a subprocess.
- **A11 — Concurrency model:** MCP hosts today mostly serialize tool calls per server; the binary still uses a small worker pool and SQLite WAL for safe concurrent calls, but no cross-*process* coordination exists (two IDE windows = two TokenGuard processes sharing one DB — WAL makes this safe, cache is shared, in-memory semantic index may briefly lag the other process's writes: accepted).
- **A12 — Install script hosting** (`tokenguard.io`), binary signing certificates, and marketing-site/dashboard integration (Lovable) are assumed provisioned by ops; M8 depends on the signing lead time.
- **A13 — Pass-through wording:** the expired-license "warning appended to LLM output" (prompt §3) is implemented as one line appended to the first tool result per session (§6.3.1) — assumed to satisfy the intent without burning tokens on every call.

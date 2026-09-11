# PLATFORM_AGNOSTIC_PLAN.md — TokenGuard Cross-Client Interception & Local Testing

> **Date:** 2026-07-09 · **Updated:** 2026-07-19 (integrated validated findings from `research_proposition.md`; see §7)  
> **Status:** Planning — not yet implemented. Execution order, definitions of done, and production-readiness items now live in `enhancement_implementation_plan.md`; this document remains the architecture/why.  
> **Prerequisite reading:** `TOKENGUARD_PLAN.md` §1.1–§1.3, `CLAUDE.md` §2.1, `evals.md`, `research_proposition.md`

---

## 0. Markdown-vs-Codebase Divergence Analysis

Before planning new work, a cross-reference of what the documentation claims versus what actually exists:

### Implemented as Documented

| Documented Feature | Source | Status |
|---|---|---|
| MCP Frontend (stdio JSON-RPC 2.0) | Plan §1.3, CLAUDE.md | ✅ Matches — hand-rolled per ADR-0002, supports 3 protocol versions |
| Engine 1 — Semantic Cache (two-tier) | Plan §6.1.1 | ✅ Matches — exact key + bag-of-words fallback per ADR-0003 |
| Engine 2 — AST Indexer (Java only) | Plan §6.1.1, A2 | ✅ Matches — JavaParser, incremental, hash-diff |
| Engine 3 — Code Mode (GraalVM JS sandbox) | Plan §6.4 | ✅ Matches — statement + time limits, circuit breaker, jailed FS |
| Engine 4 — Diff/Distill | Plan §1.3 | ✅ Matches — all 4 tools (read_file, apply_edit, list_directory, search_files) |
| Entitlement Manager | Plan §6.3 | ✅ Matches — Ed25519, 5 modes, 72h grace, background refresh |
| Team Cache | Plan §6.2.4 | ✅ Matches — HMAC + AES-GCM, 150ms race |
| Telemetry Sync | Plan §6.6.2 | ✅ Matches — daily rollups, opt-out, buffer cap |
| Tool Router (layered) | Plan §1.3 | ✅ Matches — cache → metrics → pass-through warning |
| Doctor self-check | Plan M8 | ✅ Matches — --doctor flag verifies SQLite + JS sandbox |
| Single SQLite store | Plan §4.1 | ✅ Matches — ~/.token-guard/guard.db, WAL mode |
| 7 tools in standard profile | Plan §5.1 | ✅ Matches — per evals.md test discovery |
| Profile system (standard/code-mode) | Plan §5.1 | ✅ Matches — --profile flag in Main.java |

### Documented but NOT Implemented

| Documented Feature | Source | Current State | Impact |
|---|---|---|---|
| **MCP Client Multiplexer** | Plan §1.3 "MCP Client Multiplexer" | Zero code exists. No `proxy/` package. | HIGH — This is what would make TokenGuard a true proxy of downstream MCP servers. Without it, TokenGuard cannot intercept tool calls meant for other servers. **2026-07-19: `research_proposition.md` (§4–§5, KC-1/KC-2) confirms this as the single highest-leverage gap in the product and validates the design against RAG-MCP, MCP-Zero, Speakeasy, and elusznik's proxy — see §7 below for the amended architecture.** |
| **model2vec-class embedding model** | Plan §6.1.1, A5, §2 | Fallback bag-of-words random-projection only (ADR-0003). No `embeddings/model.bin` asset. | MEDIUM — Semantic recall is limited to near-exact word-bag matches. |
| **Cache-at-rest encryption** | Plan §6.2.1 | `config.toml` has `cache.encrypt = false`; no encryption code for local blobs. | LOW — Defence against a non-threat per plan's own analysis. |
| **Enterprise Audit upload** | Plan §6.6.3 | `AuditUploader.append()` logs debug; no batch POST to `/audit`. Hash chain computed in AuditLog but never shipped. | LOW — Enterprise feature, no current customers. |
| **Proxied downstream tool namespacing** | Plan §5.1 (`ds__<server>__<tool>`) | No code — depends on MCP Client Multiplexer. | HIGH — Related to multiplexer. |
| **`mcp.call(server, tool, args)` in scripts** | Plan §5.2 | No code — depends on multiplexer. | MEDIUM — Code-mode feature enhancement. |
| **`native/` directory with GraalVM reachability metadata** | Plan §3 | Not checked in. CI may auto-generate. | LOW — CI builds successfully. |
| **`ToolProfiles.java`** | Plan §3 file layout | Profile logic is inline in `GuardWiring.wire()`, not a separate class. | LOW — Structural divergence only. |
| **`SymbolGraph.java`, `Renderers.java`, `EvictionPolicy.java`, `OutputLimiter.java`, `ApiCatalog.java`, `Distiller.java`** | Plan §3 file layout | These specific classes don't exist; their functionality is absorbed into other files (QueryEngine, SearchApiTool, CacheStore, ScriptSandbox, ReadFileTool respectively). | LOW — Refactoring choices. |

### Divergences (Implemented Differently Than Documented)

| Feature | Plan Says | Reality | Significance |
|---|---|---|---|
| Build system | "Gradle" (§3) | Maven (ADR-0001) | Documented in ADR, non-issue |
| MCP protocol implementation | "Official MCP Java SDK" (§2) with fallback | Hand-rolled (ADR-0002) — ~400 lines | Documented in ADR |
| Embedding approach | "model2vec-class static embeddings" (§2) | Bag-of-words random projection (ADR-0003) | Documented in ADR |
| Worker threading | "Worker pool" (StdioTransport) | Single-threaded executor (`newSingleThreadExecutor`) | Minor — hosts serialize calls anyway (A11) |
| Schema file naming | `SemanticIndex.java` in plan | Functionality is in `CacheLayer.java` + `CacheStore.java` | Structural, not functional divergence |
| `text.*` host API in Code Mode | Plan §5.2 lists `text.slice/count/matchAll` | Not visible in HostApi bindings (JS has native string methods) | Sensible omission — JS strings suffice |

---

## 1. Objective 1: Platform-Agnostic MCP Interception

### 1.1 What "Agnostic" Means Concretely

**The hard truth:** MCP is a *tool provider* protocol, not a *tool interceptor* protocol. An MCP server exposes tools that the LLM can *choose* to call. It cannot replace or intercept a client's own built-in file-reading operations unless the client deliberately delegates those to MCP.

This means "platform-agnostic interception" has two distinct tiers:

- **Tier A — Tool Substitution (achievable):** TokenGuard registers its tools (read_file, search_files, etc.) and the client's LLM prefers them over built-in equivalents because they exist in the tool catalog. This works when the client exposes its own file operations as tools the LLM calls — TokenGuard's tools with the same or better names/descriptions win the LLM's tool-selection logic.

- **Tier B — True Interception (mostly not achievable via MCP):** The client performs file reads *internally* (not via tool calls) to build context, and TokenGuard would need to sit in that path. This is architecturally impossible for most clients via MCP alone — it would require either (a) the client delegating its context-gathering to MCP, (b) a filesystem-level shim, or (c) the MCP Client Multiplexer from the plan.

### 1.2 Per-Client Analysis

#### Claude Code CLI

| Aspect | Detail |
|---|---|
| **MCP Registration** | Project-level `.mcp.json`, user-level `~/.claude.json` (top-level `mcpServers`), or local-project `~/.claude.json` (under `projects["/path"].mcpServers`). Also: `claude mcp add --transport stdio name -- command args...` |
| **Config Format** | `{ "mcpServers": { "name": { "type": "stdio", "command": "...", "args": [...] } } }` — supports stdio, http, sse, ws transports |
| **Built-in File Operations** | Claude Code has native `Read`, `Write`, `Edit`, `Glob`, `Grep` tools that operate locally. These are **internal tools**, not MCP tools. |
| **Can TokenGuard Substitute?** | **Partially.** Claude Code's LLM sees both its built-in tools and MCP tools in the same tool catalog. If TokenGuard's tool names conflict with built-in names, the MCP tool may or may not take priority — this is LLM-selection-dependent, not guaranteed. Claude Code may also hardcode preference for its own tools. |
| **Interception of Context Gathering** | **No.** When Claude Code reads files to build its initial context window (e.g., for `/plan`), it uses its own internal tooling, not MCP. TokenGuard never sees these reads. |
| **Slash Commands** | `/plan`, `/review`, `/mcp`, `/config` etc. are internal CLI commands. They do NOT route through MCP. `/mcp` manages MCP servers, not delegates to them. |
| **Hooks (closest to interception)** | Claude Code has a **hooks system** (`.claude/settings.json` — `PreToolUse`, `PostToolUse` events) that can observe/block tool calls via shell scripts. This is NOT MCP — it's separate infrastructure. Could potentially be used to *log* built-in tool usage alongside TokenGuard tracing, but cannot redirect reads to TokenGuard. |
| **Reserved names** | Claude Code blocks certain server names (`workspace`, `claude-in-chrome`, etc.). TokenGuard's name `token-guard` is fine. |
| **Realistic Expectation** | TokenGuard tools appear in the catalog; the LLM *may* prefer them for explicit file reads during conversation. Initial context assembly is unreachable. **Tier A partial, Tier B impossible.** |
| **Verification Needed** | ⚠️ Test whether Claude Code deduplicates tool names (does MCP `read_file` shadow built-in `Read`?) or if both appear and the LLM picks. Note: built-in tools are named `Read`, `Write`, `Edit`, `Bash`, `Glob`, `Grep` — different from TokenGuard's `read_file`, so no name collision. |

#### Claude Desktop

| Aspect | Detail |
|---|---|
| **MCP Registration** | `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows) |
| **Config Format** | `{ "mcpServers": { "name": { "command": "...", "args": [...] } } }` — stdio |
| **Built-in File Operations** | Claude Desktop has **no** built-in file-reading tools in its base chat mode. File access comes entirely from MCP servers or file attachments. |
| **Can TokenGuard Substitute?** | **Yes — strongest fit.** Claude Desktop relies entirely on MCP for filesystem access. If TokenGuard is the only MCP server providing file tools, all file reads go through it. |
| **Interception of Context Gathering** | **N/A** — Claude Desktop doesn't auto-gather context from the workspace. The user drives everything through chat. |
| **Slash Commands** | Minimal — no code-specific slash commands that auto-gather context. |
| **Realistic Expectation** | **Tier A fully achievable.** TokenGuard is the file-access layer. All reads, searches, edits route through its tools naturally. |

#### VS Code + GitHub Copilot

| Aspect | Detail |
|---|---|
| **MCP Registration** | `.vscode/mcp.json` in workspace root, VS Code profile-level `mcp.json`, `devcontainer.json`, or CLI: `code --add-mcp '{"name":"...","command":...}'`. Setting `chat.mcp.discovery.enabled` auto-detects configs from Claude Desktop/other tools. |
| **Config Format** | **Critical difference from other clients:** uses `"servers"` (not `"mcpServers"`): `{ "servers": { "name": { "command": "...", "args": [...] } } }`. Supports `"type": "http"` for remote servers. |
| **Built-in File Operations** | Copilot Chat has its own workspace tools (`#file`, `#workspace`, `#codebase`) that read files directly via VS Code's extension API. These are NOT MCP tools. Copilot's agent mode (since VS Code 1.99+) also has built-in file editing tools. |
| **Can TokenGuard Substitute?** | **Limited.** MCP tools appear alongside Copilot's built-in tools. The LLM sees both, but Copilot's system prompt likely biases toward its own native tools. There is no mechanism to disable Copilot's built-in tools in favor of MCP equivalents. |
| **Interception of Context Gathering** | **No.** Copilot's `@workspace` indexing, `#codebase` context, and the new agent mode's file reads all happen through VS Code APIs, not MCP. |
| **Slash Commands** | `/fix`, `/explain`, `/tests` etc. gather context via VS Code APIs internally. Not MCP-routed. |
| **Realistic Expectation** | TokenGuard tools appear in the tool list; the LLM might use them for some operations. But Copilot's native tools will handle most file operations. **Tier A weak, Tier B impossible.** |
| **Verification Needed** | ⚠️ Test whether Copilot agent mode's built-in `read_file` conflicts with or defers to an MCP server's `read_file`. |

#### VS Code + Cline

| Aspect | Detail |
|---|---|
| **MCP Registration** | Cline sidebar → Settings → MCP Servers panel. Config stored at VS Code's global storage path: `~/.vscode/extensions/.../globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json`. Also manageable via Cline UI. |
| **Config Format** | `{ "mcpServers": { "name": { "command": "...", "args": [...], "disabled": false, "autoApprove": ["tool_name"], "timeout": 60 } } }`. Supports stdio, sse, streamableHttp transports. |
| **Built-in File Operations** | Cline has its own `read_file`, `write_to_file`, `search_files`, `list_files` tools. These are Cline-internal, sent as tool calls from the LLM but executed by Cline's extension code. |
| **Can TokenGuard Substitute?** | **Partially, with friction.** Cline exposes MCP tools to the LLM, but its own tools have the same names (`read_file`, etc.). The LLM sees both sets. Cline's system prompt instructs the LLM to use Cline's built-in tools for file operations. MCP tools are typically used for *additional* capabilities, not replacements. |
| **Interception of Context Gathering** | **No.** Cline's context window management reads files directly through VS Code APIs. |
| **Slash Commands** | Cline doesn't have traditional slash commands in the same sense — it's a conversational agent. |
| **Realistic Expectation** | TokenGuard's tools appear alongside Cline's built-in tools. The LLM will typically prefer Cline's tools (the system prompt guides it there). TokenGuard's unique tools (`query_codebase_structure`, `execute_script`, `guard_status`) will be used, but `read_file`/`search_files` will likely lose to Cline's versions. **Tier A partial (unique tools only), Tier B impossible.** |
| **Key Insight** | Cline's architecture is the closest to "all operations are tool calls" — but they're Cline-internal tool calls, not MCP tool calls. The MCP layer is additive, not substitutive. |

#### Cursor IDE

| Aspect | Detail |
|---|---|
| **MCP Registration** | `.cursor/mcp.json` in project root, or `~/.cursor/mcp.json` globally |
| **Config Format** | `{ "mcpServers": { "name": { "command": "...", "args": [...] } } }` |
| **Built-in File Operations** | Cursor has native `read_file`, `edit_file`, `codebase_search`, `grep_search`, `file_search` tools built into its agent. These are NOT MCP tools. |
| **Can TokenGuard Substitute?** | **Limited.** Cursor's agent mode has strong preference for its built-in tools. MCP tools appear in the catalog but the system prompt directs the LLM to use Cursor's natives first. There is no documented way to disable or deprioritize Cursor's built-in tools. |
| **Interception of Context Gathering** | **No.** Cursor's `@codebase`, `@file`, and Cmd+K context gathering happens via internal indexing (embeddings-based), not MCP. |
| **Slash Commands** | Cursor's internal operations (codebase indexing, @ mentions) are entirely client-side. |
| **Realistic Expectation** | TokenGuard's unique tools (query_codebase_structure, execute_script) will be available. File-operation tools will be deprioritized vs Cursor's built-ins. **Tier A partial (unique tools), Tier B impossible.** |

#### Windsurf (now Devin Desktop — acquired by Cognition, May 2025)

| Aspect | Detail |
|---|---|
| **MCP Registration** | `~/.codeium/windsurf/mcp_config.json` (official canonical path per docs.devin.ai). Older references cite `~/.windsurf/mcp.json` (may still work for backward compat). |
| **Config Format** | `{ "mcpServers": { "name": { "command": "...", "args": [...] } } }`. Unique features: supports `${env:VAR}` and `${file:/path}` interpolation in config values. Also supports `"serverUrl"` for HTTP MCP. |
| **Hard Limit** | ⚠️ **100 tools total** across all connected MCP servers. TokenGuard's 7 tools are well within budget, but be aware if user has many other servers. |
| **Built-in File Operations** | Cascade agent has its own file tools (read, write, search, terminal). These are internal. |
| **Can TokenGuard Substitute?** | **Limited** — same pattern as Cursor. MCP tools are additive, not substitutive for built-in operations. |
| **Interception of Context Gathering** | **No.** Cascade's "Flows" and automatic context gathering are internal. |
| **Realistic Expectation** | Same as Cursor — unique TokenGuard tools are available, file tools are deprioritized. **Tier A partial, Tier B impossible.** |

### 1.3 The Interception Problem — Honest Assessment

**Summary:** For most clients, TokenGuard cannot truly *intercept* file reads. It can only *offer an alternative*. The LLM's tool selection is the arbitration layer, and most clients bias their LLM toward built-in tools via system prompts.

**Where genuine interception IS achievable:**

1. **Claude Desktop** — No built-in file tools. TokenGuard IS the file layer. Full interception.
2. **Any client in "MCP-only mode"** — If a client can be configured to use MCP tools exclusively (no built-in file tools), TokenGuard wins by being the only option.

**Where interception is NOT achievable via MCP:**

- **Initial context gathering** (Cursor's @codebase, Copilot's #workspace, Cline's auto-context) — These read files internally, never touching MCP.
- **Built-in tool preference** — When the client's system prompt says "use read_file" and both a built-in and MCP version exist, the LLM almost always picks the built-in.

### 1.4 Proposed Interception Mechanism

Given the above constraints, the strategy splits into three layers:

#### Layer 1: Direct Tool Provision (works everywhere MCP is supported)

TokenGuard registers its 7 tools. For clients that have no built-in file tools (Claude Desktop), this is complete interception. For others, the unique tools (`query_codebase_structure`, `execute_script`, `guard_status`) provide value that no built-in tool matches.

**No code change needed** — this is the existing architecture.

#### Layer 2: MCP Client Multiplexer (plan §1.3 — currently unimplemented)

Implement the multiplexer so TokenGuard can proxy *other* MCP servers. This means:
- User configures ALL their MCP servers inside TokenGuard's config
- TokenGuard is the ONLY server in the IDE's `mcp.json`
- TokenGuard spawns/manages downstream servers, routes their tool calls through its cache/distill layers
- All downstream tool results get cached and optimized

This converts TokenGuard from "an extra server" to "the only server" — maximizing what flows through it.

**This is the highest-impact unimplemented feature for the interception objective.**

**Amendment (2026-07-19, from `research_proposition.md` §2.2/§5 KC-1):** the original plan-§5.1 exposure model — re-registering every downstream tool namespaced as `ds__<server>__<tool>` — is **superseded**. Static re-registration reproduces the schema-bloat problem TokenGuard exists to fix (tool schemas cost 300–500 tokens each; selection accuracy degrades sharply past ~30–100 tools in context; Windsurf hard-caps at 100 tools — Open Question 10). Instead, downstream capability is exposed through a **fixed three-tool dynamic facade**:

- `search_tools(query)` — embedding/keyword search over downstream tool summaries (reuses the Tier-2 `Embedder` and its identity-scoping machinery)
- `describe_tools(names[])` — full JSON schemas promoted on demand only
- `execute_tool(name, args)` — invocation, routed through the existing CacheLayer → Metrics chain

The facade's context footprint is fixed regardless of how many downstream servers/tools exist (evidence: ~50–98% schema-token reduction with *higher* selection accuracy — RAG-MCP, MCP-Zero, Tool Attention, Speakeasy's 100%-success 40–400-tool benchmark; citations in `research_proposition.md` §2.2). Its known cost — 2–3x more tool-call round-trips on downstream work — is accepted and disclosed. Optional compatibility switch: `proxy.expose = facade | namespaced` for hosts/users that want flat registration, defaulting to `facade`.

#### Layer 3: System Prompt Injection via Tool Descriptions (works for LLM-selection-based clients)

For clients where both built-in and MCP tools appear in the catalog, TokenGuard can make its tools more appealing:
- Tool descriptions explicitly state they're optimized ("Token-optimized file reading — returns diffs on re-reads, 94% fewer tokens than raw reads")
- Tool names match built-in conventions where possible
- Some LLMs will prefer the more capable tool when descriptions are compelling

**This is a soft strategy — no guarantee, but zero-cost to implement via better tool descriptions.**

#### Layer 4: Companion Proxy (DEVIATION — optional, for Tier B)

For true interception of *internal* client file reads (not via MCP), a different architectural approach would be needed:

**Option A: Filesystem FUSE/Filter Driver**  
A virtual filesystem layer that intercepts file reads at the OS level, serves cached/optimized content, and logs all access. This would intercept *everything*, regardless of client.

- **Unlocks:** True Tier B interception for all clients including internal context gathering.
- **Costs:** Massive complexity. Requires platform-specific code (FUSE on Linux/macOS, minifilter on Windows). Second runtime component. Security surface. Incompatible with the "single binary" philosophy.
- **Verdict:** ❌ Not recommended for v1. Overkill for the stated testing objective.

**Option B: HTTP MCP Transport (SSE)**  
Instead of stdio, run TokenGuard as an HTTP/SSE MCP server. Some clients support remote MCP servers — this doesn't help with interception but may help with multi-workspace setups.

- **Unlocks:** Multi-workspace support without multiple binary instances.
- **Costs:** Adds an HTTP server to the binary. Port management.
- **Verdict:** Noted for future consideration, doesn't solve interception.

**Recommendation:** Implement Layers 1-3. Layer 4 options are logged for future consideration but are not justified for local testing.


### 1.5 Fallback Behavior

When TokenGuard cannot optimize an operation:

1. **Operation type TokenGuard doesn't recognize**: Pass through cleanly. The multiplexer (once implemented) routes to the downstream server. Without the multiplexer, this means the tool call fails with a clear error ("Unknown tool"), and the client's own tools handle it — no silent failure.

2. **Engine failure during optimization**: Per existing design (§6.7), fall back to raw content. The interaction is still logged in H2 with `was_passthrough = true`.

3. **Client doesn't route a particular operation via MCP**: TokenGuard never sees it — nothing to intercept, nothing to log. This is the honest outcome for internal context gathering in Cursor/Copilot/Cline.

4. **License expired / pass-through mode**: Tools still function, return raw results, once-per-session warning appended. All interactions still logged (tracing is independent of optimization).

**Key principle:** The tracing layer is unconditional — even passthrough/error cases get logged. The optimization layer is best-effort with defined fallbacks.

### 1.6 Testing Strategy — Verification Per Client

For each client, the verification question is: "Did TokenGuard actually handle this operation, and did it actually save tokens?"

#### Universal Verification (works for any client)

```bash
# After a session, query the SQLite trace database bellow just an interaction example the command used need to be fixed :
java -cp 
  -url "h2:~/.token-guard/trace" \
  -sql "SELECT client_name, COUNT(*), SUM(tokens_saved), ROUND(AVG(savings_pct),1) 
        FROM interaction_trace 
        WHERE session_id = (SELECT session_id FROM interaction_trace ORDER BY timestamp_utc DESC LIMIT 1)
        GROUP BY client_name"
```

#### Claude Desktop Verification

1. Add TokenGuard to `claude_desktop_config.json`
2. Open a chat, ask Claude to "read the file README.md in [workspace]"
3. Ask the same question again
4. Call `guard_status` tool (ask Claude to "check token-guard status")
5. **Expected:** First read returns full content. Second read returns "unchanged." guard_status shows 1 cache hit, >90% savings.
6. **Definitive proof:** The interaction_trace table shows 2 `read_file` entries with `engine_used = 'diff_distill'`, second entry has `optimization = 'unchanged_reread'`.

#### Claude Code CLI Verification

1. Add TokenGuard to `.mcp.json` in the project root
2. Start a claude session: `claude`
3. Ask: "Use the token-guard read_file tool to read pom.xml" (explicit tool name forces MCP)
4. Ask again: "Use token-guard read_file to read pom.xml again"
5. **Expected:** Second read returns "unchanged"
6. **Key test:** Then ask "Read pom.xml" (without specifying tool) — check trace DB to see if Claude Code's built-in Read or TokenGuard's read_file was used.
7. **Definitive proof:** `SELECT operation_type, client_name FROM interaction_trace ORDER BY timestamp_utc DESC LIMIT 5` — if TokenGuard handled it, there's a row.

#### Cursor Verification

1. Add TokenGuard to `.cursor/mcp.json`
2. Open Composer (agent mode)
3. Ask: "Use the query_codebase_structure tool to show me the outline of Main.java"
4. **Expected:** Structural outline returned (~10 lines vs 93-line file)
5. **Key test:** The unique tools (query_codebase_structure, execute_script) WILL be used because Cursor has no equivalent built-in. File read tools likely won't be preferred.
6. **Definitive proof:** Trace DB shows entries for Cursor sessions using `query_codebase_structure`.

#### VS Code + Copilot Verification

1. Add TokenGuard to `.vscode/mcp.json`
2. In Copilot Chat (agent mode), ask: "Use the @token-guard execute_script tool to count Java files"
3. **Expected:** Returns a number (not file list)
4. **Limitation:** Copilot's built-in workspace tools will handle most file operations; TokenGuard's unique tools prove the connection works.
5. **Definitive proof:** Any row in trace DB with `client_name` containing 'copilot' or matching VS Code's client info.

#### VS Code + Cline Verification

1. Add TokenGuard via Cline's MCP settings panel
2. In a Cline conversation, ask: "Use the guard_status MCP tool"
3. **Expected:** Returns TokenGuard status dashboard
4. **Key test:** Ask Cline to use `query_codebase_structure` explicitly — this tool doesn't conflict with Cline's built-ins.
5. **Definitive proof:** Trace DB shows entries with Cline's client identifier.

#### Windsurf Verification

1. Add TokenGuard to `~/.codeium/windsurf/mcp_config.json`
2. Same as Cursor — test unique tools (query_codebase_structure, execute_script)
3. **Definitive proof:** Trace DB entries with Windsurf client identifier.

#### Automated Verification Script (for CI/local testing)

```bash
#!/bin/bash
# Pipe a known MCP session to TokenGuard and verify trace DB populates
set -e

WORKSPACE="$(pwd)"
TRACE_DB="$HOME/.token-guard/trace"

# Run a scripted MCP session
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"verification-test","version":"1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"guard_status","arguments":{}}}' \
  | java -jar core/target/token-guard.jar --workspace "$WORKSPACE" > /dev/null 2>&1

# Query trace DB
RESULT=$(java -cp "lib/h2-*.jar" org.h2.tools.Shell \
  -url "jdbc:h2:$TRACE_DB" \
  -sql "SELECT COUNT(*) as calls, SUM(tokens_saved) as saved FROM interaction_trace WHERE session_id = (SELECT MAX(session_id) FROM interaction_trace)" 2>/dev/null)

echo "Verification result: $RESULT"
# Expected: calls=3 (guard_status doesn't count as file op), saved > 0
```

---

## 2. Objective 2: README Section for Local Setup

### Draft Content (to be added to README.md)

```markdown
## Local Setup — Making a Repo TokenGuard-Aware

### Prerequisites

- **JDK 21** (any distribution: Oracle GraalVM, Eclipse Temurin, Amazon Corretto)
- **Maven ≥ 3.9** (only if building from source; the native binary needs nothing)
- **TokenGuard binary** — either:
  - Built from source: `mvn -q -DskipTests package` → `core/target/token-guard.jar`
  - Native image: `mvn -Pnative -DskipTests package` → `core/target/token-guard` (requires GraalVM)
  - Downloaded: `curl -sSf https://tokenguard.io/install.sh | sh` (macOS/Linux) or `irm https://tokenguard.io/install.ps1 | iex` (Windows)

### Quick Start (60-second smoke test)

```bash
# 1. Build (if from source)
cd token-guard && mvn -q -DskipTests package

# 2. Run the self-check
java -jar core/target/token-guard.jar --doctor --workspace .
# Expected output: "All checks passed"

# 3. Pipe a quick MCP session to verify optimization
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  | java -jar core/target/token-guard.jar --workspace . 2>/dev/null | tail -1
# The last response should contain "unchanged since you last read it" — that's optimization working.
```

### Per-Client Configuration

#### Claude Desktop

**File:** `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)  
or `%APPDATA%\Claude\claude_desktop_config.json` (Windows)

```json
{
  "mcpServers": {
    "token-guard": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/core/target/token-guard.jar", "--workspace", "/path/to/your/project"],
      "env": {}
    }
  }
}
```

Or with the native binary:
```json
{
  "mcpServers": {
    "token-guard": {
      "command": "/usr/local/bin/token-guard",
      "args": ["--workspace", "/path/to/your/project"],
      "env": {}
    }
  }
}
```

**Restart Claude Desktop after editing.** TokenGuard's 7 tools will appear in your tool list.

#### Claude Code CLI

**File:** `.mcp.json` in your project root (per-project) or `~/.claude.json` (user-level, under top-level `mcpServers`).

Or register via CLI:
```bash
claude mcp add --transport stdio token-guard -- java -jar /absolute/path/to/core/target/token-guard.jar --workspace .
```

Manual `.mcp.json`:
```json
{
  "mcpServers": {
    "token-guard": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/core/target/token-guard.jar", "--workspace", "."],
      "env": {}
    }
  }
}
```

**Note:** Claude Code has its own built-in file tools. TokenGuard's unique tools (`query_codebase_structure`, `execute_script`, `guard_status`) provide additional capabilities. For file operations, the LLM may choose either — TokenGuard's tools are most valuable when re-reading files (diff optimization) and for structural queries.

#### Cursor

**File:** `.cursor/mcp.json` in your project root or `~/.cursor/mcp.json` (global)

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

**Restart Cursor after editing.** Check Cursor Settings → MCP to verify "token-guard" shows as connected.

#### VS Code + GitHub Copilot

**File:** `.vscode/mcp.json` in your workspace root

```json
{
  "servers": {
    "token-guard": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/core/target/token-guard.jar", "--workspace", "${workspaceFolder}"]
    }
  }
}
```

**Requires:** VS Code 1.99+ with GitHub Copilot extension. MCP servers appear in Copilot Chat's tool list.

#### VS Code + Cline

**Via UI (recommended):** Cline sidebar → Settings (gear icon) → MCP Servers → Add Server → fill in command and args.

**Config file location:** Stored at VS Code's extension global storage:  
`~/.vscode/extensions/.../globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json`  
(exact path depends on extension version; use the UI to avoid hunting for it)

```json
{
  "mcpServers": {
    "token-guard": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/core/target/token-guard.jar", "--workspace", "."],
      "disabled": false,
      "timeout": 60
    }
  }
}
```

#### Windsurf (Devin Desktop)

**File:** `~/.codeium/windsurf/mcp_config.json`

```json
{
  "mcpServers": {
    "token-guard": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/core/target/token-guard.jar", "--workspace", "."],
      "env": {}
    }
  }
}
```

### What "Seamless" Means in Practice

**What you SHOULD notice:**
- A `guard_status` tool appears in your agent's tool list
- If you ask `guard_status`, you see live savings statistics
- Structural queries (`query_codebase_structure`) return concise outlines instead of full files
- `execute_script` lets your agent run precise JS data-extraction scripts

**What you SHOULD NOT notice:**
- No visible latency increase — TokenGuard adds < 5ms per tool call
- No changed behavior for file edits — `apply_edit` works identically to before (but returns a diff confirmation instead of the full file)
- No prompts, popups, or consent dialogs — it's transparent
- No network calls blocking your workflow — all cloud features are async with timeouts

**What changes under the hood (invisible to you, visible in `guard_status`):**
- Second reads of unchanged files return "unchanged" (1 line) instead of full content
- Modified file re-reads return a unified diff instead of the full file
- Identical search queries are served from cache
- The codebase is incrementally indexed for structural queries

### Troubleshooting

| Symptom | Likely Cause | Fix |
|---|---|---|
| "No tools found" / server doesn't appear | Path to JAR is wrong or not absolute | Use full absolute path, not relative. Check the file exists: `ls /path/to/core/target/token-guard.jar` |
| `UnsupportedClassVersionError` | JDK < 21 on PATH | Install JDK 21+ and ensure `java -version` shows 21+ |
| Server starts but immediately exits | Missing `--workspace` argument | Add `--workspace` pointing to your project root |
| "token-guard" shows as "errored" in client | Startup crash — check stderr | Run manually: `java -jar token-guard.jar --workspace . --debug 2>debug.log` and inspect `debug.log` |
| Tools appear but return errors | SQLite native lib issue (rare on some platforms) | Run `--doctor` to diagnose; try the JAR version instead of native image |
| Windows: "Access is denied" on guard.db | Another TokenGuard instance has the DB locked | Close other IDE instances using TokenGuard, or set `--cache-dir` to a different path per project |
| No optimization visible (guard_status shows 0% savings) | Client is using its own built-in tools, not TokenGuard's | Expected for some clients (Cursor, Copilot). Try `query_codebase_structure` or `execute_script` which have no built-in equivalent |
| Slow first response after startup | AST indexer running on a large workspace | Normal — indexing is async and non-blocking. Subsequent calls are fast. |
| "License expired" warning in tool output | Old entitlement token, no connectivity to refresh | Set `--license-key` or ignore — free-local mode still optimizes |
```

---

## 3. Objective 3: Verifying Interception of Client-Native Commands

### 3.1 Core Question

Does a client's slash command or built-in command route its execution through MCP at all? If not, TokenGuard cannot intercept it — period.

### 3.2 Per-Client Analysis

#### Claude Code CLI — Slash Commands (`/plan`, `/review`, `/compact`, etc.)

| Question | Answer |
|---|---|
| **Does it route through MCP?** | **No — not for context gathering.** Claude Code's slash commands assemble context by calling its own internal `Read`, `Glob`, `Grep` tools. These are extension-internal, not MCP calls. The resulting context is sent to the LLM as part of the prompt, and the LLM's *response* may then invoke MCP tools — but the context-gathering phase is already done. |
| **What crosses the MCP boundary?** | Only explicit tool calls *in the LLM's response*. If the LLM decides to call `read_file` (MCP) during its reasoning, that goes through TokenGuard. But the context that *triggered* the LLM call was gathered internally. |
| **Can TokenGuard proxy here?** | **No.** TokenGuard cannot reduce the context that Claude Code assembles for the initial prompt. It can only optimize subsequent tool calls the LLM makes during its response. |
| **Fallback** | **Observation only** — TokenGuard can log whatever tool calls DO reach it, but cannot observe or optimize the internal context assembly. For `/plan`, the heavy context work (reading relevant files, gathering codebase info) happens before TokenGuard ever sees anything. |
| **Verification method** | Run `claude` with TokenGuard configured. Execute `/plan "refactor auth"`. After completion, query trace DB: `SELECT COUNT(*) FROM interaction_trace WHERE session_id = ...` — if zero, the entire operation was internal. If non-zero, those are the subsequent LLM-initiated tool calls that DID route through TokenGuard. Compare: ask the same question conversationally ("help me plan a refactor of auth") — this forces the LLM to request tools, which WILL go through TokenGuard. |

#### Claude Desktop — No Relevant Slash Commands

| Question | Answer |
|---|---|
| **Does it route through MCP?** | **N/A** — Claude Desktop doesn't have code-specific slash commands. All file access is through tool calls (MCP or built-in Computer Use). |
| **Can TokenGuard proxy here?** | **Yes — fully.** Every file operation the LLM requests goes through MCP. |
| **Verification** | Ask Claude to read a file. Check trace DB — guaranteed to have an entry. |

#### VS Code + GitHub Copilot — Slash Commands (`/fix`, `/explain`, `/tests`, `/new`)

| Question | Answer |
|---|---|
| **Does it route through MCP?** | **No for context, partially for execution.** When you run `/fix` on selected code, Copilot gathers the file content + selection + diagnostics through VS Code APIs internally. The LLM receives this as pre-assembled context. The LLM's *response* may include tool calls (in agent mode), which could include MCP tools. |
| **What crosses the MCP boundary?** | In agent mode: tool calls in the LLM's response. In inline/panel mode: nothing — it's a direct prompt→completion, no tool calls at all. |
| **Can TokenGuard proxy here?** | **No for context gathering. Partially for subsequent tool calls in agent mode.** The selected code, file content, and diagnostics that form the prompt are assembled by the extension, not via MCP. |
| **Fallback** | Logging-only for whatever tool calls DO reach TokenGuard. The `/fix` prompt assembly is entirely unreachable. |
| **Verification** | In agent mode, run a complex task. Check trace DB for entries. Compare with running the same task in Copilot Chat with explicit "use token-guard read_file" instructions — the explicit version should show entries while the slash-command version likely shows none or fewer. |

#### VS Code + Cline — Conversational (no traditional slash commands)

| Question | Answer |
|---|---|
| **Does it route through MCP?** | **Partially.** Cline's architecture is "everything is a tool call from the LLM" — but they're Cline's *own* tool calls, not MCP tool calls. The LLM sees Cline's tools (read_file, write_to_file, etc.) and MCP tools in one merged catalog. Cline's system prompt directs the LLM to prefer Cline's built-in tools. |
| **What crosses the MCP boundary?** | MCP tools that the LLM explicitly chooses. In practice: tools that don't have a Cline built-in equivalent (e.g., `query_codebase_structure`, `execute_script`). |
| **Can TokenGuard proxy here?** | **For unique tools, yes. For file reads, unlikely** — the LLM prefers Cline's `read_file` over TokenGuard's because the system prompt says to. |
| **Verification** | Ask Cline to "use query_codebase_structure to outline Main.java" (explicit). Check trace DB. Then ask "show me the structure of Main.java" (implicit) — see if Cline's LLM picks TokenGuard's tool or reads the file with its own built-in tool. |

#### Cursor — Slash Commands and Built-in Operations

| Question | Answer |
|---|---|
| **Does it route through MCP?** | **No for all context gathering and most slash-command-like features.** Cursor's `@codebase` search, Cmd+K, and agent-mode file operations use Cursor's own tools. The embeddings-based codebase index is entirely internal. |
| **What crosses the MCP boundary?** | Only explicit MCP tool calls the LLM makes. Cursor's agent will use MCP tools when they provide unique capabilities not available in its built-ins. |
| **Can TokenGuard proxy here?** | **Only for unique tools.** Cursor's `read_file`, `edit_file`, `codebase_search` etc. are preferred by its system prompt. TokenGuard's `query_codebase_structure` and `execute_script` have no built-in equivalent — these will be used. |
| **Verification** | In Composer (agent mode), ask "use the execute_script tool to find all classes implementing Tool interface." Check trace DB. The unique tools prove the connection; file tools likely show zero usage. |

#### Windsurf — Cascade Commands

| Question | Answer |
|---|---|
| **Does it route through MCP?** | **Same pattern as Cursor.** Cascade gathers context internally (file reads, codebase search via Codeium's backend). MCP tools are available but not preferred for operations where built-ins exist. |
| **Can TokenGuard proxy here?** | **Only for unique tools.** Same as Cursor. |
| **Verification** | Same as Cursor verification approach. |

### 3.3 Summary Matrix

| Client | Context Gathering | Subsequent Tool Calls | TokenGuard Can Optimize |
|---|---|---|---|
| **Claude Desktop** | Via MCP (no built-in) | Via MCP | ✅ Everything |
| **Claude Code CLI** | Internal (unreachable) | Via MCP (LLM-selected) | ⚠️ Only LLM-initiated tool calls, not context assembly |
| **VS Code + Copilot** | Internal (unreachable) | Via MCP (agent mode only) | ⚠️ Only agent-mode tool calls |
| **VS Code + Cline** | Internal (Cline tools) | Via MCP (LLM-selected) | ⚠️ Only unique tools (query_codebase_structure, execute_script) |
| **Cursor** | Internal (unreachable) | Via MCP (LLM-selected) | ⚠️ Only unique tools |
| **Windsurf** | Internal (unreachable) | Via MCP (LLM-selected) | ⚠️ Only unique tools |

### 3.4 Honest Conclusion

**TokenGuard as a genuine proxy (reducing what gets sent to the LLM) is achievable only for Claude Desktop.** For all other clients, TokenGuard optimizes the *response* path (tool results that go back into context), not the *request* path (context the client assembles for the prompt). This is still valuable — tool results are a major token consumer — but it is not "interception of everything the LLM sees."

**For slash commands specifically:** They are genuinely unreachable by any MCP-level mechanism in all clients except Claude Desktop. This is an honest architectural limitation, not a solvable engineering problem. No amount of clever server design changes the fact that a client's internal file-reading code doesn't call MCP.

### 3.5 What IS Actually Reducible (and worth optimizing)

Even with the above limitations, TokenGuard's value proposition holds:

1. **Tool result optimization** — When the LLM *does* call read_file (through TokenGuard or otherwise), subsequent calls to the same file return diffs or "unchanged". This is the single biggest token saver in multi-turn conversations.

2. **Structural queries** — `query_codebase_structure` has no equivalent in any client's built-in tools. It's universally available and universally useful.

3. **Code Mode** — `execute_script` collapses multi-tool-call sequences into one precise result. No client has an equivalent.

4. **Cache across re-invocations** — Even if the client reads a file internally first, and the LLM then asks TokenGuard to read it, TokenGuard's diff-on-re-read still saves tokens on that second (MCP) read.

---

## 4. Open Questions

1. **Claude Code tool deduplication:** When TokenGuard's `read_file` and Claude Code's built-in `Read` both appear in the tool catalog, how does Claude's LLM decide which to use? Is there a priority system, or is it purely LLM reasoning? This needs empirical testing.

2. **Copilot agent mode MCP priority:** Does VS Code's Copilot agent mode give any preference to MCP tools over built-in tools, or can this be configured? The `github.copilot.chat.mcp.servers` setting exists but tool priority behavior is undocumented.

3. **Cline tool namespace conflicts:** When Cline's `read_file` and TokenGuard's `read_file` have the same name, does Cline error, rename, or merge? Need to test empirically.

4. **H2 + Native Image compatibility:** H2 2.x is pure Java and should work, but need to validate that the GraalVM native-image build doesn't require additional reflection metadata for H2. The Maven plugin *should* auto-generate it, but this is untested.

5. **MCP `initialize` clientInfo reliability:** Do all clients actually send useful `clientInfo` in the initialize handshake? If some send empty or generic info, client identification in the trace DB may be unreliable.

6. **Multi-workspace scenarios:** If a developer has TokenGuard configured in multiple projects (multiple `mcp.json` files), do trace DB entries from different workspaces coexist cleanly? The `session_id` should disambiguate, but needs verification.

7. **Cursor MCP tool name conflicts:** If TokenGuard exposes `read_file` and Cursor has a built-in `read_file`, does Cursor's MCP integration reject the server, rename the tool, or present both?

8. **Rate of MCP spec evolution:** The protocol is at `2025-06-18`. If newer versions add "tool priority" or "tool replacement" capabilities, that would change the interception story significantly. Monitor the spec.

9. **Claude Code hooks as supplementary tracing:** Claude Code's `PreToolUse`/`PostToolUse` hooks (`.claude/settings.json`) could potentially be used to log built-in tool usage *alongside* TokenGuard's H2 trace — giving visibility into what TokenGuard missed. Worth exploring whether a hook script could write to the same H2 trace DB, creating a unified view of all operations (MCP + built-in). This wouldn't enable interception, but would enable complete observability.

10. **Windsurf 100-tool limit impact:** Windsurf caps total MCP tools at 100 across all servers. TokenGuard's 7 tools are fine, but if the multiplexer (plan §1.3) is implemented and proxies many downstream servers, the namespaced tools (`ds__<server>__<tool>`) could hit this ceiling. May need a dynamic registration strategy for Windsurf specifically. **RESOLVED 2026-07-19:** the dynamic three-tool facade (§1.4 Layer 2 amendment) makes the exposed tool count constant (+3) regardless of downstream size; the ceiling is only reachable in the optional `namespaced` compatibility mode.

---

## 5. Deviations Log

| # | Deviation | Default Path | Proposed | Reasoning | Cost |
|---|---|---|---|---|---|
| 1 | ~~**H2 for tracing**~~ **WITHDRAWN 2026-07-19** | SQLite (existing store) | ~~H2 database for interaction_trace~~ → **`interaction_trace` table in the existing SQLite `guard.db`** | The original H2 proposal contradicted `TOKENGUARD_PLAN.md` §6.1.2 (which explicitly rejected H2) and this document's own §6 step 1 ("flyway … with needed **sqlite** scripts"), and left Open Question 4 (H2 + native-image) unresolved. Reconciliation per the most-authoritative source (`TOKENGUARD_PLAN.md` §2/§6.1.2): one storage engine, already native-image-proven, WAL handles append-heavy trace writes, and users inspect one file with standard SQLite tooling. Discrepancy noted rather than silently dropped. | Trace queries share the writer with cache writes (WAL mitigates); no free web console (acceptable — `guard_status` and the CLI cover inspection). |
| 2 | **Separate trace concern, same store** | Single guard.db for everything | `interaction_trace` as its own table (own DAO, no FK into cache/index tables) | Tracing remains a separate concern (append-heavy, never transactionally coupled to cache/index) but does not justify a second database engine. Corruption story is unchanged: guard.db is disposable by design (plan §6.7.2). | None beyond schema v2 migration. |
| 3 | **Tool descriptions as soft interception** | Tool descriptions are functional only | Descriptions that explicitly advertise optimization ("returns diffs, 94% fewer tokens") | For clients where LLM selects between built-in and MCP tools, a more compelling description may tip the balance. Zero-cost, potentially high-value. | Risk of sounding "salesy" in tool descriptions. LLM behavior is unpredictable — may not help. |
| 4 | **No filesystem-level interception** | Not in existing plan | Explicitly rejected for v1 | The complexity of FUSE/filter drivers is disproportionate to the testing objective. True interception at the FS level would require platform-specific code, a second runtime, and introduces security surface. The honest answer is: what clients read internally stays internal. | Means Tier B interception is not achievable. Accepted. |

---

## 6. Implementation Sequence

**Superseded 2026-07-19.** The sequence below was tracing-centric and predates the research validation. The authoritative execution order — with sequencing dependencies, definitions of done, and production-readiness items — is now `enhancement_implementation_plan.md`. The original steps are preserved there (Phase 0: P0.2 trace layer via SQLite, P0.3 tool descriptions; Phase 2: verification scripts and per-client empirical testing; Phase 4: README local-setup section).

---

## 7. Research Integration (2026-07-19) — Architecture-Level Conclusions Adopted from `research_proposition.md`

This section records *what the architecture now assumes*; the evidence and citations live in the research document and are not duplicated here.

### 7.1 Strategy posture

1. **The multiplexer is promoted from "optional component" to the product's strategic center.** The market has moved from "provide better tools" to "optimize the session's entire tool surface." TokenGuard's four local engines are the substrate; the multiplexer + facade (§1.4 Layer 2 amendment) is the wedge. The plan-§5.1 static `ds__` namespacing is superseded by the dynamic facade (with `namespaced` retained as an opt-in compatibility mode).
2. **Code mode is extended to downstream composition.** `mcp.call(server, tool, args)` (already specified in plan §5.2) becomes the second pillar: scripts compose downstream tools inside the sandbox so intermediate results never enter model context. Precondition: the Oracle-GraalVM sandbox resource limits (plan §6.4, `sandbox.MaxCPUTime`/`MaxHeapMemory`) must be wired **before** scripts gain downstream reach.
3. **Honest measurement is a product feature, not plumbing.** `chars/4` is replaced by BPE-aware counting, and savings claims move from self-reported percentages to a reproducible benchmark reporting **medians with ranges**. This is the credibility wedge against a market of inflated self-reported numbers.

### 7.2 Capability adoptions (architecture level)

| Adopted | Source basis | Architectural note |
|---|---|---|
| Dynamic toolset facade (3 fixed tools) | research §2.2 (RAG-MCP, MCP-Zero, Tool Attention, Speakeasy) | Facade reuses Tier-2 `Embedder` for `search_tools`; downstream results flow through the existing `ToolRouter` layer chain unmodified |
| Downstream host-config import | research §2.3 (elusznik precedent: 12+ standard config locations) | One-command onboarding: TokenGuard reads the host's existing `mcp.json`-family configs and offers to absorb them |
| Graph-ranked repo map under a token budget | research §2.4 (Aider design) | New `get_repo_map` query type over the **existing** `symbols`/`symbol_edges` tables; PageRank + binary-search fit; no schema change |
| Session memory / compaction recovery | research §2.3 (context-mode) | Rolling session journal in SQLite + one `restore_context` tool; builds on `FileStateTracker` |
| Negative-ROI guard for structural answers | research §2.4 (code-review-graph admission) | Any structural/summary response is compared against the raw read/diff alternative; the cheaper representation wins |
| Multi-language structural indexing | research §2.4 (universal in competitors) | **Constraint conflict flagged:** tree-sitter is C/JNI; plan §2's native-image rule kills naive JNI. Requires an M0-style spike (statically-linked JNI à la xerial *or* pure-Java parsers) + ADR before commitment. Java stays on JavaParser. |
| Hook-based routing (per-host, optional) | research §2.1/§2.3 (token-optimizer-mcp, context-mode) | Not MCP — a per-host add-on pack; host capability matrix governs (see §3.2/§4 Q9) |

### 7.3 Explicit non-adoptions (recorded so they aren't relitigated)

- **In-server LLMLingua-class prompt compression** — a tool provider does not own the prompt; highest-friction fit (research §3, criterion C3). Revisit only as an optional distillation pass on oversized tool outputs.
- **Blob compression (Brotli-class) as a headline feature** — saves disk, not context-window tokens; the anchor competitor's conflation of the two is a cautionary example, not a model.
- **Growing the exposed tool count** — the >30-tool selection-accuracy cliff makes TokenGuard's small surface (7 standard / 2 code-mode / +3 facade) an asset to protect.
- **Filesystem-level interception (FUSE/minifilter)** — unchanged from Deviation 4; still rejected.

---

*End of plan. Execution: see `enhancement_implementation_plan.md`.*

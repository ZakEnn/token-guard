# How to Use TokenGuard — and See the Savings Yourself

> Every number in this document is either **[MEASURED]** — from an actual run
> against this repo (Windows 11, Temurin JDK 21, 2026-07-19, 103 Java files
> indexed) or the reproducible benchmark in [`bench/RESULTS.md`](bench/RESULTS.md) —
> or explicitly labeled **[ILLUSTRATIVE]**. Your absolute numbers will differ
> with your repo; the mechanisms and ratios are what you are verifying.

---

## 1. What TokenGuard actually is, and what actually fires

TokenGuard is a **local MCP stdio server**. It is triggered one way: your
agent host (Claude Code, VS Code+Copilot, Cursor, Claude Desktop, …) spawns
the binary from its MCP config and the **LLM chooses TokenGuard's tools** from
the tool catalog. TokenGuard never sees your prompt or the LLM call itself —
it shrinks what flows back into context as *tool results*. (This matters for
the plan-mode scenarios below — read the caveats there.)

The mechanisms, by their real names in this codebase:

| Mechanism | Engine | Tool(s) that trigger it | What it does |
|---|---|---|---|
| **Diff-on-reread** | 4 Diff/Distill | `read_file` | Re-read of an unchanged file → one line; changed file → unified diff only |
| **Edit-returns-diff** | 4 | `apply_edit` | Edit ack is a diff — no verify-re-read needed |
| **Structural queries** | 2 AST index | `query_codebase_structure` (outline/symbol/callers/…) | Signatures instead of file bodies. **Java files only (v1)** |
| **Repo map** | 2 | `query_codebase_structure` `query_type=repo_map` | Whole-repo signature map, PageRank-ranked, fitted to `map_tokens` budget |
| **Negative-ROI guard** | 2 | outline | If the outline would cost ≥ the raw read, you get a cheap redirect instead |
| **Result cache** | 1 Semantic cache | transparent (all cacheable tools) | Exact-key SHA-256 tier + conservative semantic tier (bag-of-words fallback per ADR-0003 — near-exact wording only) |
| **Code Mode** | 3 GraalVM JS sandbox | `execute_script`, `search_api` | The agent's script returns only the slice it computed |
| **Downstream composition** | 3 + proxy | `mcp.call(server,tool,args)` in scripts | Intermediate downstream results never enter context |
| **Proxy facade** | Multiplexer | `search_tools` / `describe_tools` / `execute_tool` | Your *other* MCP servers behind 3 fixed tools — schemas load lazily, results are cached |
| **Session memory** | — | `restore_context` | Post-compaction state rebuild instead of re-reading everything |
| **Hook gate** (Claude Code, optional) | — | `install-hooks` | PreToolUse deny-gate steering native Read/Grep/Glob to TokenGuard equivalents |

**How you measure:** TokenGuard counts tokens with an **exact BPE tokenizer**
(cl100k_base via JTokKit) and tracks, per call, the *returned* size **and the
naive-equivalent `raw` size** (what the un-optimized answer would have cost).
Three read-outs:

1. `guard_status` tool — live session totals (`raw → returned`, cache tiers, per-client detail).
2. The `interaction_trace` table in `<data-dir>/guard.db` — one row per call, queryable with any SQLite client.
3. `mvn -pl bench -am verify` — the reproducible A/B benchmark (medians with ranges).

---

## 2. One-time setup (clean checkout)

```bash
cd token-guard
mvn -q -DskipTests package          # -> core/target/token-guard.jar  (JDK 21+, Maven 3.9+)
java -jar core/target/token-guard.jar --doctor --workspace .
# Expected: sqlite OK, js sandbox OK, "All checks passed."
```

Everything below uses the JAR; substitute the native binary path if you built
one with `-Pnative`.

---

## 3. Part 0 — Prove the mechanisms in 5 minutes, no IDE required

These pipe raw MCP JSON-RPC into the binary — the exact frames an IDE sends.
Fastest way to see real numbers with zero host variables. (`bash` shown;
PowerShell piping also works — TokenGuard tolerates the BOM PowerShell
prepends.)

### 3.1 Diff-on-reread [MEASURED]

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"me","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"guard_status","arguments":{}}}' \
  | java -jar core/target/token-guard.jar --workspace . --no-telemetry
```

Measured on this repo:
- First read: full content, **10,265 chars**.
- Second read: `README.md: unchanged since you last read it.` — **45 chars**.
- `guard_status`: `tokens (cl100k_base (exact BPE)): raw 4852 -> returned 2809 (saved ~2043, 42%)`

The 42% is the whole session *including* the unavoidable first read; the
re-read itself went from ~2,000 tokens to ~13 (**>99%**). Edit the file
between reads and the second response is a unified diff of the change only.

### 3.2 The multi-step session: structure + code mode + cache [MEASURED]

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"me","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"query_codebase_structure","arguments":{"query_type":"outline","target":"core/src/main/java/io/tokenguard/cache/CacheLayer.java"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"query_codebase_structure","arguments":{"query_type":"repo_map","map_tokens":800}}}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"execute_script","arguments":{"script":"fs.glob(\"**/*.java\").length"}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_files","arguments":{"pattern":"TraceLayer","glob":"**/*.java","max_results":5}}}' \
  '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"search_files","arguments":{"pattern":"TraceLayer","glob":"**/*.java","max_results":5}}}' \
  '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"guard_status","arguments":{}}}' \
  | java -jar core/target/token-guard.jar --workspace . --no-telemetry
```

Measured on this repo:
- Outline of `CacheLayer.java`: **1,219 chars** vs the **5,702-char** file (~79% smaller).
- `repo_map` (800-token budget): **3,339 chars** covering the whole 103-file codebase, hub files ranked first.
- `execute_script`: returns **`103`** (3 chars) instead of listing 103 paths.
- Repeated identical `search_files`: served from the exact-key cache — `cache: 1/5 hits [exact 1, semantic 0, team 0]`.
- **Session total: `raw 13712 -> returned 1372 (saved ~12340, 89%)`.**

### 3.3 The reproducible A/B benchmark [MEASURED]

The only fully-controlled "with vs without" comparison — a fixed task set run
against TokenGuard *and* a naive baseline provider, exact BPE counting,
byte-identical output across reruns:

```bash
mvn -pl bench -am verify
# -> bench/RESULTS.md
```

Current medians (ranges in RESULTS.md): re-read-heavy editing **51.2%**,
navigation **82.3%**, structural query **41.0%**, downstream composition
**99.8%**.

---

## 4. Part 1 — Claude Code (CLI)

### Setup

```bash
cd your-project
claude mcp add --transport stdio token-guard -- java -jar /ABSOLUTE/path/to/core/target/token-guard.jar --workspace .
# or write .mcp.json:  { "mcpServers": { "token-guard": { "command": "java",
#   "args": ["-jar", "/ABSOLUTE/path/.../token-guard.jar", "--workspace", "."] } } }
claude          # start a session; /mcp should list token-guard with its tools
```

**Reading the numbers in this environment:** Claude Code's `/cost` includes
model output and its own overhead, so it is noisy for A/B. The clean
instrument is TokenGuard's own accounting — ask for `guard_status` at any
point, or query the trace DB after the session:

```bash
sqlite3 ~/.token-guard/guard.db "SELECT tool, engine, optimization, raw_tokens, returned_tokens \
  FROM interaction_trace ORDER BY id DESC LIMIT 10;"
```

`raw_tokens` is the naive-equivalent cost per call — that column **is** your
"without TokenGuard" number for every call that routed through it.

### 4a. Simple prompt

**Prompt:** `Use the token-guard read_file tool to read core/pom.xml, then read it again with the same tool and tell me if it changed.`

- **Without TokenGuard** (fresh session, no server registered, same prompt minus "token-guard"): Claude Code's built-in Read returns the full file both times — for this repo's `core/pom.xml` that is ~1,450 tokens twice **[ILLUSTRATIVE — obtain yours from `/cost` deltas or the file size ÷ ~4]**.
- **With TokenGuard:** first call full content, second call is the one-line "unchanged" notice. *Mechanism: diff-on-reread.* Verify: `guard_status` shows the saved tokens; the trace row for the second call carries `optimization = unchanged_reread` **[MEASURED mechanism — §3.1]**.
- **Delta:** ~50% across the pair, >99% on the re-read itself.
- **Caveats:** on a single-turn "explain this function" prompt with *one* read, TokenGuard saves nothing on the read itself — savings start at the second touch of any file. Overhead is one spawned process and <5 ms per call. Without the explicit "token-guard" nudge (or the hook gate, §4d), Claude Code's LLM may pick its built-in `Read` — the built-ins and TokenGuard's tools coexist and selection is the model's choice.

### 4b. Complex multi-step agentic task

**Prompt:** `Using only the token-guard tools: get a repo_map of this codebase, then find every class implementing the Tool interface, show me the outline of the three most important ones, and count the test files. Do not read any file in full.`

- **Without TokenGuard:** the equivalent built-in workflow greps, then reads several files in full — for this repo that's ≥5 full files ≈ 10–15k tokens **[ILLUSTRATIVE]**.
- **With TokenGuard:** `repo_map` (~800 tokens, budget-capped), `query_codebase_structure` outlines (~300 tokens each), `execute_script` count (single number). *Mechanisms: repo map, structural queries, code mode.* The §3.2 session is exactly this shape and measured **89% saved** (`raw 13712 → returned 1372`) **[MEASURED]**.
- **Delta:** 80–90% for navigation/understanding-heavy tasks (bench medians: navigation 82.3%, structure 41.0%).
- **Caveats:** structural queries are **Java-only** in v1 — on a TS/Python repo, `query_codebase_structure` returns a no-result note and the savings come from diff-on-reread, cache, and code mode instead. The negative-ROI guard means tiny signature-dense files may answer "use read_file" — that's correct behavior, not a failure.

### 4c. Plan mode — honest limitation, and what you *can* measure

**What actually happens:** Claude Code's plan-mode **context assembly is
internal** — it reads files through its own machinery, *never via MCP*.
TokenGuard cannot see or reduce that phase. This is an architectural property
of MCP (tool provider, not interceptor), documented in
`PLATFORM_AGNOSTIC_PLAN.md` §3. What *does* flow through TokenGuard: every
tool call the LLM chooses to make while elaborating or executing the plan.

**Prove it to yourself:**

```bash
# in a claude session with token-guard registered:
#   1) enter plan mode and ask:
Plan a refactor of the caching layer in this repo.
#   2) after the plan is produced, exit and run:
sqlite3 ~/.token-guard/guard.db "SELECT COUNT(*) FROM interaction_trace \
  WHERE ts > strftime('%s','now','-10 minutes');"
```

- Rows ≈ 0 during pure planning → the assembly was internal (nothing to optimize, nothing lost).
- Then ask: `Execute step 1 of the plan using token-guard tools` — rows appear, and the plan→execute transition benefits fully: files the plan phase already read get diff-on-reread on their *first* TokenGuard touch only after edits; `restore_context` (§4d) carries state across compactions mid-plan.
- **Delta:** 0% on plan assembly (unreachable by design — stated, not glossed); execution-phase calls save per §4a/§4b.

### 4d. Claude Code–specific extra triggers

**Hook gate (enforcement instead of hoping):**

```bash
java -jar core/target/token-guard.jar install-hooks --host claude-code --workspace .
# writes a PreToolUse gate into .claude/settings.json (backup kept);
# native Read/Grep/Glob get a deny + "use mcp__token-guard__read_file" steer.
# Requires the `token-guard` binary on PATH (the gate runs `token-guard hook-gate`).
java -jar core/target/token-guard.jar uninstall-hooks --workspace .   # byte-identical restore
```

> **Status honesty:** the install/uninstall/backup mechanics are tested
> (`HooksCommandsTest`); the *live* deny behavior depends on your Claude Code
> version's hook schema and has not yet been validated against real host
> versions (enhancement plan P3.4 is PARTIAL, gated on the P2.3 client
> campaign). Try it; `uninstall-hooks` gets you back to your exact prior
> settings.

**Session recovery:** after Claude Code compacts context mid-task, one call to
`restore_context` returns a dense summary (files read/edited, errors, recent
calls) instead of the agent re-reading everything — measured <500 tokens for
a five-operation session (`SessionJournalTest`).

**Proxy facade — TokenGuard as your only MCP server** (needs Node 18+ for
typical downstream servers):

```bash
java -jar core/target/token-guard.jar import-servers --dry-run --workspace .   # lists what it found
java -jar core/target/token-guard.jar import-servers --workspace .             # writes [servers.*] into ~/.token-guard/config.toml
# then remove those servers from .mcp.json, leaving only token-guard
```

Your downstream tools now appear as exactly three tools
(`search_tools`/`describe_tools`/`execute_tool`) — a 40-tool server costs the
same context as a 4-tool one, read-style results are cached, and scripts can
compose them via `mcp.call` without the intermediate payloads touching
context. Composition measured at **99.8% median** reduction in the bench
(T4) **[MEASURED in bench]**; the facade's fixed footprint and
read-verb-only caching are covered by `FacadeTest`. Costs one extra
search→describe round-trip per newly-discovered tool — for one-shot calls to
an already-known tool the facade adds a step; it wins as tool count and
session length grow.

---

## 5. Part 2 — VS Code + GitHub Copilot

TokenGuard integrates with Copilot **as an MCP server** (VS Code 1.99+,
agent mode). There is no other Copilot integration path.

### Setup

`.vscode/mcp.json` in your workspace — note the key is **`servers`**, not
`mcpServers`:

```json
{
  "servers": {
    "token-guard": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/path/to/core/target/token-guard.jar", "--workspace", "${workspaceFolder}"]
    }
  }
}
```

Reload; TokenGuard's tools appear in Copilot Chat's agent-mode tool list.

**Reading the numbers:** Copilot does not expose per-request token usage
locally, so the instrument is again TokenGuard's own accounting
(`guard_status`, trace DB — same SQL as §4). The trace rows will show
`client_name` from Copilot's handshake, giving you per-client attribution.

> **Honest framing for all Copilot scenarios:** Copilot's system prompt
> biases its LLM toward its *built-in* workspace tools for plain file
> operations. TokenGuard's reliably-used surface here is the tools that have
> **no built-in equivalent**: `query_codebase_structure`, `execute_script`,
> `restore_context`, `guard_status`, and the proxy facade. The per-client
> empirical campaign that would quantify selection rates (enhancement plan
> P2.3) is not yet done — `docs/CLIENT_MATRIX.md` is the template awaiting
> those runs.

### 5a. Simple prompt

**Prompt:** `Using the token-guard read_file tool, read core/pom.xml twice and report whether it changed.`

Same mechanism and numbers as §4a (diff-on-reread is host-independent —
§3.1's measured session is the ground truth). **Caveat:** without naming the
tool, Copilot will likely use its own file reading and TokenGuard sees
nothing — `guard_status` showing `calls: 0` is itself the honest measurement
of that.

### 5b. Complex multi-step agentic task

**Prompt:** `Use the token-guard tools to give me a repo_map of this project, outlines of the three most-referenced classes, and the total count of Java test files — without pasting any full file contents.`

Mechanisms and measured deltas identical to §4b (**89% measured** for the
equivalent session shape, §3.2). Copilot benefits *more* from this pattern in
relative terms, because its built-in alternative (`#codebase` expansions,
full-file reads) is otherwise invisible and unoptimizable.

### 5c. Plan-mode prompt

Copilot has no user-facing plan mode equivalent to Claude Code's; its closest
behaviors (Copilot Edits' working-set assembly, `@workspace` context) gather
context **through VS Code APIs, not MCP** — TokenGuard cannot intercept them,
same architectural boundary as §4c. **There is no plan-mode trigger to
demonstrate for Copilot; claiming one would be fiction.** What you can do:
run a multi-file editing session in agent mode instructing tool use
(`use token-guard for all file reads and edits`) and compare the
`interaction_trace` totals against the same session without the instruction —
rows with `optimization` set (`unchanged_reread` / `diff_reread`) are the
savings that fired.

### 5d. Copilot-specific extras

- The **proxy facade** works identically to §4d (config is TokenGuard-side,
  host-agnostic). Windsurf-style tool caps don't apply to VS Code, but the
  fixed 3-tool footprint still keeps Copilot's tool list small.
- The **hook pack does not support Copilot** (`install-hooks` supports
  `--host claude-code` only; anything else exits with an "Unsupported"
  message). Stated per the no-invention rule.

---

## 6. Cross-scenario summary

Labels: **M** = measured in this repo/bench (see section), **I** = illustrative estimate, **N/A** = mechanism architecturally cannot fire.

| Environment | Scenario | Tokens without | Tokens with | Reduction | Mechanism | Label |
|---|---|---|---|---|---|---|
| stdio (no IDE) | Re-read pair (§3.1) | raw 4,852 | 2,809 | **42%** session / >99% on the re-read | diff-on-reread | M |
| stdio (no IDE) | 5-call research session (§3.2) | raw 13,712 | 1,372 | **89%** | repo map + outline + code mode + cache | M |
| bench harness | Re-read-heavy editing | median 2,397 | 1,167 | **51.2%** (36.6–54.1) | diff-on-reread + diff-edit-ack | M |
| bench harness | Navigation | median 798 | 140 | **82.3%** (44.4–89.6) | search + ranged read | M |
| bench harness | Structural query | median 798 | 471 | **41.0%** (35.3–42.0) | outline | M |
| bench harness | Downstream composition | median 1,950 | 4 | **99.8%** | mcp.call in code mode | M |
| Claude Code | a. simple re-read | ~2,900 (2 full reads) | ~1,460 | ~50% pair / >99% re-read | diff-on-reread | I (mechanism M) |
| Claude Code | b. multi-step research | 10–15k | 1–2k | 80–90% | repo map + outlines + script | I (session-shape M: 89%) |
| Claude Code | c. plan assembly | — | — | **0% — internal to host, unreachable via MCP** | none | N/A |
| Claude Code | c'. plan *execution* calls | per-call | per-call | as rows a/b | all engines | M mechanism |
| Claude Code | d. facade + mcp.call | 2 raw payloads | 1 summary line | 99.8% median | proxy + code mode | M (bench) |
| Copilot | a–b (tool-named prompts) | as Claude Code | as Claude Code | as above | same engines | I (mechanism M) |
| Copilot | c. plan mode | — | — | **no plan-mode equivalent exists** | none | N/A |
| Copilot | d. hooks | — | — | **not supported for Copilot** | — | N/A |

## 7. Global caveats (read before quoting numbers)

1. **Savings compound with session length.** A cold, single-read session
   saves little; the second touch of anything is where every engine pays.
2. **Tool selection is the LLM's choice** in hosts with built-in file tools.
   Name the tools in your prompt, or (Claude Code) install the hook gate.
   `guard_status` → `Session detail` → `calls:` tells you honestly how much
   actually routed through TokenGuard.
3. **Cache hits save recomputation, not context** — a cache-served result
   still enters context at full size. That's why §3.2's hit contributes to
   latency, not the 89%.
4. **Java-only AST** (v1) and **near-exact-only semantic matching**
   (ADR-0003 fallback embedder) are the two biggest current limiters —
   tracked in `enhancement_implementation_plan.md` (P3.1, P4.5a).
5. Numbers here were captured at repo state 2026-07-19 (179-test suite
   green); rerun §3 and the bench on your checkout — that's the point of
   this document.

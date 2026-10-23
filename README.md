# TokenGuard

A local MCP server that cuts LLM token consumption on agentic coding by
returning **fewer, denser tokens per tool call** — structural code summaries
instead of whole files, diffs instead of full re-reads, cached tool results,
a scripting mode that collapses many tool calls into one, and a **proxy mode**
that multiplexes your *other* MCP servers through the same optimization
layers (so TokenGuard can be the only server in your `mcp.json`).

 <img width="886" height="264" alt="image" src="https://github.com/user-attachments/assets/4b0c9d0c-c8d1-4f4c-a17f-7c48a2d57d22" />

Measured savings (medians with ranges, exact BPE counting, reproducible):
see [`bench/RESULTS.md`](bench/RESULTS.md).

Built per [`TOKENGUARD_PLAN.md`](../TOKENGUARD_PLAN.md). Java 21, compiles to a
GraalVM native image (no JRE needed by end users), speaks MCP over stdio.

> **How it actually saves tokens** (plan §1.1): an MCP stdio server is a *tool
> provider* — it cannot see or skip the LLM call. Token spend in agentic coding
> is dominated by *tool output* and *tool schemas*, and that is exactly what
> TokenGuard shrinks. Marketing that says "skips the LLM" is inaccurate; the
> honest mechanism is tool-output and tool-schema reduction.

## The four engines

| Engine | Tool(s) | What it does |
|---|---|---|
| **Diff / Distill** | `read_file`, `apply_edit`, `list_directory`, `search_files` | Re-reads return a diff, not the file. Edits return the diff, not the file. Reads are ranged and capped. |
| **AST index** | `query_codebase_structure` | Class/method outlines, signatures, callers/callees, hierarchy, symbol search — from a JavaParser index. ~90% smaller than reading files. Java only (v1). |
| **Semantic cache** | (transparent) | Two tiers: exact-key, then a conservative semantic match on free-text queries. Per-entry dependency hashes invalidate exactly the affected entries when code changes. |
| **Code Mode** | `execute_script`, `search_api` | The agent writes a small sandboxed JS program against a read-only workspace API and gets back only the slice it needs. `mcp.call(server, tool, args)` composes downstream tools in-script. `--profile code-mode` exposes just these two tools. |
| **Proxy / multiplexer** | `search_tools`, `describe_tools`, `execute_tool` | Your other MCP servers, spawned and managed by TokenGuard, exposed through a fixed 3-tool facade — tool schemas stay out of context until actually needed, and every downstream result is cached and traced. |
| **Session memory** | `restore_context` | One call rebuilds working state (files read/edited, errors, recent calls) after host context compaction or a restart. |

`query_codebase_structure` also serves `repo_map`: a whole-repo signature map
ranked by reference importance (PageRank) and fitted to a token budget
(default 1000).

`guard_status` reports live hit rate, tokens saved (exact BPE counting),
per-tier cache splits, per-client session detail, index freshness, license
mode, and degradation state. Set `status.price_per_mtok` in config.toml to
see an estimated `$` saved.

## Install from GitHub Releases

Each release ships one self-contained jar, `token-guard-<version>.jar`, with
its `token-guard-<version>.jar.sha256` checksum and a signed build-provenance
attestation. The jar isn't code-signed and there are no native binaries yet.

**Requires JDK 21+.** `java -version` must report 21 or newer (any
distribution: Temurin, Corretto, Oracle, GraalVM).

**1. Download** both files from the
[latest release](https://github.com/ZakEnn/token-guard/releases/latest)
(pre-releases such as `-rc1` are on the
[releases page](https://github.com/ZakEnn/token-guard/releases) and aren't
"latest"):

```bash
VERSION=0.1.0   # the release you picked, without the leading "v"
curl -fLO "https://github.com/ZakEnn/token-guard/releases/download/v${VERSION}/token-guard-${VERSION}.jar"
curl -fLO "https://github.com/ZakEnn/token-guard/releases/download/v${VERSION}/token-guard-${VERSION}.jar.sha256"
# or, with the GitHub CLI:  gh release download "v${VERSION}" --repo ZakEnn/token-guard
```

**2. Verify the checksum** before running it. If the output isn't `OK` (or
the hashes differ), delete the file and don't run it.

```bash
sha256sum -c "token-guard-${VERSION}.jar.sha256"          # Linux
shasum -a 256 -c "token-guard-${VERSION}.jar.sha256"      # macOS
```
```powershell
# Windows PowerShell
$Version  = "0.1.0"
$expected = (Get-Content "token-guard-$Version.jar.sha256").Split(" ")[0]
$actual   = (Get-FileHash "token-guard-$Version.jar" -Algorithm SHA256).Hash
if ($actual -ieq $expected) { "OK" } else { "MISMATCH - do not use this file" }
```

**3. Optional: verify build provenance.** This proves the jar was built by
this repo's `release.yml` workflow from the tagged commit:

```bash
gh attestation verify "token-guard-${VERSION}.jar" --repo ZakEnn/token-guard
```

**4. Put it in place and run the self-check:**

```bash
mkdir -p ~/.token-guard
mv "token-guard-${VERSION}.jar" ~/.token-guard/token-guard.jar
java -jar ~/.token-guard/token-guard.jar --doctor --workspace .
# Expected: sqlite OK, js sandbox OK, "All checks passed."
```
(PowerShell: `New-Item -ItemType Directory -Force "$HOME\.token-guard"` then
`Move-Item "token-guard-$Version.jar" "$HOME\.token-guard\token-guard.jar"`.)

**5. Register it with your client.** Use `"command": "java"` and `"args":
["-jar", "/ABSOLUTE/path/to/.token-guard/token-guard.jar", "--workspace",
"/ABSOLUTE/path/to/project"]`. JSON configs don't expand `~`, so spell out
your home directory. The config file and key for each client are listed in
[Per-client configuration](#per-client-configuration) below. Add
`--no-telemetry` to the args to opt out of anonymous usage rollups (see
[Security posture](#security-posture)).

## Install (hosted installer: planned, NOT live yet)

> **Not yet live:** the hosted installers and license purchase flow below are
> the planned distribution path (`enhancement_implementation_plan.md` P4.2/P4.3)
> and **don't work yet**. The `tokenguard.io` URLs aren't served. Until
> then, use [Install from GitHub Releases](#install-from-github-releases)
> above or build from source (next section).

```
curl -sSf https://tokenguard.io/install.sh | sh      # macOS / Linux  (planned, not live)
irm https://tokenguard.io/install.ps1 | iex          # Windows PowerShell  (planned, not live)
```

Then add to your IDE's `mcp.json` (see the snippet the installer prints).
Without a license key it runs in **free-local mode** (local optimization,
100 MB cache). A key unlocks the full cache, shared team cache, and analytics.

## Build from source

```
cd token-guard
mvn test                       # 179 tests (JDK 21)
mvn -q -DskipTests package     # core/target/token-guard.jar (+ dependency/), and
                               # core/target/token-guard-all.jar (self-contained)
mvn -Pnative -DskipTests package   # native image (needs GraalVM)
```

Run against a workspace:
```
java -jar core/target/token-guard.jar --workspace /path/to/repo
token-guard --doctor           # self-check (native binary)
```

Useful flags: `--profile code-mode` (2-tool profile), `--no-telemetry`,
`--log-json`, `--debug`, `--license-key KEY`.

## Local Setup — Making a Repo TokenGuard-Aware

### 60-second smoke test

```bash
mvn -q -DskipTests package
java -jar core/target/token-guard.jar --doctor --workspace .

printf '%s\n' \
  '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1.0"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}}}' \
  | java -jar core/target/token-guard.jar --workspace . 2>/dev/null | tail -1
# The last response contains "unchanged since you last read it" — optimization working.
```

### Per-client configuration

All clients: point the config at the JAR (`"command": "java", "args": ["-jar",
"/absolute/path/to/core/target/token-guard.jar", "--workspace", "."]`) or at
the native binary (`"command": "token-guard"`). **Use absolute paths.**

| Client | Config file | Key |
|---|---|---|
| Claude Desktop | `%APPDATA%\Claude\claude_desktop_config.json` (Win) / `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) | `mcpServers` |
| Claude Code CLI | `.mcp.json` in project root, or `claude mcp add --transport stdio token-guard -- java -jar … --workspace .` | `mcpServers` |
| Cursor | `.cursor/mcp.json` (project) or `~/.cursor/mcp.json` | `mcpServers` |
| VS Code + Copilot | `.vscode/mcp.json` | **`servers`** (not `mcpServers`) |
| VS Code + Cline | Cline sidebar → Settings → MCP Servers (UI recommended) | `mcpServers` |
| Windsurf | `~/.codeium/windsurf/mcp_config.json` | `mcpServers` |

Restart the client after editing. What to expect per client: Claude Desktop
routes **all** file access through TokenGuard (no built-in file tools); agents
with built-in file tools (Claude Code, Cursor, Copilot, Cline, Windsurf) will
reliably use TokenGuard's *unique* tools (`query_codebase_structure`,
`execute_script`, `restore_context`, the proxy facade) and may prefer their
built-ins for plain reads — see `docs/CLIENT_MATRIX.md`.

### Proxy mode — TokenGuard as your only MCP server

```bash
# absorb the servers from your existing IDE configs (dry-run first):
token-guard import-servers --dry-run
token-guard import-servers
```

This copies your other MCP servers into `~/.token-guard/config.toml`
`[servers.*]` blocks. Then leave token-guard as the only entry in the IDE
config. Downstream tools are exposed through three fixed tools
(`search_tools` → `describe_tools` → `execute_tool`), so a 40-tool server
costs the same context as a 4-tool one; read-style results are cached. Set
`proxy.expose = "namespaced"` for flat `ds__server__tool` registration
instead (costs context, can hit host tool caps).

Optional, Claude Code only: `token-guard install-hooks --host claude-code`
adds a PreToolUse gate steering native Read/Grep/Glob to TokenGuard's
equivalents (`uninstall-hooks` restores your settings byte-identically).

### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "No tools found" / server missing | JAR path not absolute or wrong | Use the full absolute path; check the file exists |
| `UnsupportedClassVersionError` | JDK < 21 | Install JDK 21+; `java -version` must show 21+ |
| Exits immediately with a workspace warning | `--workspace` points at a nonexistent directory | Pass a real project directory |
| Server "errored" in the client | Startup crash | Run manually with `--debug 2>debug.log` and read the log |
| 0% savings in guard_status | Client used its built-in tools | Expected on some clients; try `query_codebase_structure` / `execute_script` |
| Slow first response | AST indexer warming up on a big repo | Normal; async — subsequent calls are fast |
| Downstream server "unavailable" | It crashed and failed its one restart | Fix its command in `[servers.*]`; restart TokenGuard |
| "License expired" warning | Stale entitlement, offline | Free-local mode still optimizes; set `--license-key` to restore cloud tiers |

## Layout

```
core/       the shipped binary (mcp, cache, astindex, codemode, distill,
            entitlement, teamcache, telemetry, store, saas, util)
backend/    Supabase migrations + Edge Functions (licensing, team cache, audit)
install/    install.sh / install.ps1
docs/adr/   architecture decision records (deviations from the plan)
```

## Security posture

Every decision traces to a threat in the plan's §6:
- Script sandbox: no host/network/process access, read-only workspace jail with
  symlink resolution, secret-file deny-list, CPU/statement/output limits + watchdog (§6.4).
- `apply_edit` is the **only** write path; scripts cannot modify files (§6.4).
- Team cache: per-team HMAC keys (not dictionary-attackable) + client-side
  AES-256-GCM; the platform stores only ciphertext (§6.2.4).
- License keys: never stored raw (only `sha256`); exchanged for a short-lived
  JWT; expiry degrades to raw pass-through, never a hard failure (§6.3).
- Telemetry: daily rollups only, no code/paths/prompts, opt-out (§6.6.4).
- TokenGuard never fails a tool call: every engine has a defined fallback (§6.7).

See [`docs/adr/`](docs/adr) for where the implementation deviates from the plan
(hand-rolled MCP framing, Maven, and the fallback embedder pending the model asset).

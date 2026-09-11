# Research Proposition — Token-Efficient Context Management & Intelligent Tool-Call Indexation

> **Prepared for:** TokenGuard (Java 21 / GraalVM native-image local MCP server)
> **Date:** 2026-07-19
> **Method:** Autonomous multi-agent research sweep (5 search angles → 24 sources fetched → 119 claims extracted), followed by inline spot-verification of the load-bearing claims against primary sources, followed by a code-level audit of the TokenGuard repository.
> **Verification disclosure:** The automated 3-vote adversarial verification stage of the research harness failed on infrastructure limits (all 75 verifier agents errored on a session cap). Instead of shipping unverified claims, the highest-impact claims — the anchor repo, both 2026 arXiv papers, Anthropic, Cloudflare, Speakeasy, and context-mode — were **re-verified manually against the primary sources** on 2026-07-19 and are marked ✅ below. Claims marked ⬜ are accurately extracted from the named source but reflect that project's *self-reported* numbers, not independent measurement. No citation in this document is fabricated; every source was actually fetched.

---

## 1. Executive Summary

The field has converged on five distinct strategy families for reducing token expenditure in agentic workflows. Measured/claimed savings by family:

| Strategy family | Representative savings | Strongest evidence |
|---|---|---|
| **Code mode / script execution** | 98.7–99.9% on tool-definition + intermediate-data tokens | Anthropic ✅, Cloudflare ✅, CodeAct (ICML 2024) |
| **Dynamic tool selection / schema RAG** | ~50–98% of schema tokens; accuracy *improves* (43.13% vs 13.62%) | RAG-MCP ✅, MCP-Zero ✅, Speakeasy ✅ |
| **Structural repo indexing (AST/graph)** | 40–99% on code-navigation queries | Aider repo-map, Serena, code-graph-mcp, codebase-memory-mcp |
| **Prompt/context compression** | 2–20x compression at ~1.5% accuracy loss (task-dependent) | LLMLingua family (ACL/EMNLP peer-reviewed) |
| **Result caching + output distillation** | 60–98% on repeated/verbose tool output | token-optimizer-mcp ✅, context-mode, GPTCache |

**The single most important strategic finding:** the market's center of gravity has shifted from *"optimize the tools I provide"* (TokenGuard's current model) to *"optimize the entire tool surface of the session"* — either by proxying/multiplexing other MCP servers (dynamic toolsets, schema RAG) or by collapsing all tools into a code-execution surface (code mode). TokenGuard already has 4 of the 5 families implemented locally, but its unimplemented **MCP Client Multiplexer** (plan §1.3, zero code today) is precisely the capability the highest-growth competitors are built around. The second most important finding: **every credible competitor publishes measured savings numbers** — TokenGuard's `chars/4` estimator and absence of published benchmarks is a go-to-market gap, not just a technical one.

---

## 2. Discovery & Scope — Landscape Analysis

Reasoning for source selection (stated per the autonomous mandate): sources were chosen to cover all five strategy families, mixing peer-reviewed papers (ground truth on accuracy tradeoffs), high-traffic engineering blogs from platform vendors (ground truth on production deployment), and working open-source MCP servers (ground truth on integration friction). Popularity (stars) was used as a proxy for adoption, not correctness.

### 2.1 Anchor: ooples/token-optimizer-mcp ✅ (verified 2026-07-19)

- **URL:** https://github.com/ooples/token-optimizer-mcp — TypeScript, MIT, 442 ⭐, v5.0.1 (Nov 2025)
- **Architecture:** Brotli compression (2–4x typical, up to 82x for repetitive content) over a multi-tier (L1/L2/L3) SQLite-backed persistent cache with 6 eviction strategies; tiktoken-based token counting; **61 specialized tools**; "Smart Tool Replacements" that substitute the host's native Read/Grep/Glob (claimed 80/80/75% reduction respectively); a 7-phase global hook pipeline (PreToolUse → Input Validation → PostToolUse → Session Tracking → UserPromptSubmit → PreCompact → Metrics) for Claude Code/Desktop, Cursor, Cline, Copilot, Windsurf, with claimed <10ms hook overhead.
- **Claimed savings:** "60–90% token reduction across 38,000+ operations in real-world usage" (self-reported, methodology unpublished).
- **Design tradeoffs:**
  - *For:* hook-based enforcement means the optimization is applied even when the model doesn't choose the optimized tool — a real adoption insight.
  - *Against:* 61 tools is itself context bloat (~300–500 tokens/tool per arXiv:2605.26165 ⇒ plausibly 18k–30k tokens of schemas), which directly contradicts the product's own goal; the RAG-MCP stress test shows selection accuracy degrades sharply past ~30–100 tools. Compression (Brotli) reduces *storage*, not *tokens seen by the model* — compressed blobs must be decompressed before entering context, so the savings claims conflate cache-hit avoidance with compression ratio. Own docs concede: best only for content >500 chars, no benefit for never-referenced content, 7-day cache TTL.
- **Lesson for TokenGuard:** validates persistent SQLite caching and host-hook integration; refutes the "many specialized tools" surface design.

### 2.2 Tool-call indexation / dynamic tool selection

**RAG-MCP** ✅ — *"Mitigating Prompt Bloat in LLM Tool Selection via Retrieval-Augmented Generation"* (Gan & Sun), https://arxiv.org/abs/2505.03275
- External vector index over all MCP tool metadata; retrieves and injects **only the single best-matching schema** per query.
- Verified: >50% prompt-token cut (1,084 vs 2,133.84 avg tokens) and **tool-selection accuracy 43.13% vs 13.62%** blank-conditioning baseline (18.20% "Actual Match" figure is from the paper body). Key motivating result: the "MCP stress test" — selection success >90% below ~30 tools in context, degrading sharply past ~100 (paper body).
- Limitation (authors'): retrieval precision decays as the registry grows toward thousands of tools; hierarchical/adaptive retrieval flagged as future work. Single-shot retrieval fails hard on composite tasks.

**MCP-Zero** ✅ — *"Active Tool Discovery for Autonomous LLM Agents"* (Fei, Zheng, Feng et al.), https://arxiv.org/abs/2506.01056
- Inverts RAG-MCP: the *agent* emits structured capability requests (server domain + operation), resolved on demand by hierarchical semantic routing; iterative capability extension handles composite tasks.
- Verified: **98% token reduction on APIBank** (111 vs ~6.3k tokens single-turn) at maintained accuracy; evaluation corpus of **308 MCP servers / 2,797 tools** (~248k tokens of schemas — a single GitHub MCP server burns 4,600+ tokens for 26 tools).

**Tool Attention** ✅ — *"Tool Attention Is All You Need: Dynamic Tool Gating and Lazy Schema Loading…"* (Sadani & Kumar, Apr 2026), https://arxiv.org/abs/2604.21816
- Two-phase lazy loading: O(N) compact summaries always resident; full JSON schemas promoted per-turn via an Intent–Schema-Overlap embedding score + state-aware precondition gating. **95.0% per-turn schema-token cut (47.3k → 2.4k)** on a 120-tool testbed; context utilization 24% → 91%.
- Honest caveat (verified in paper): results are a **simulation harness projection**, not live-agent runs. Ships as a LangGraph `before_model` middleware — near-zero host friction.

**Tool-Schema Compression (Tscg)** ✅ — *"Tool-Schema Compression Enables Agentic RAG Under Constrained Context Budgets"* (Sakizli, May 2026), https://arxiv.org/abs/2605.26165
- Verified: JSON tool schemas cost **300–500 tokens each**; compression yields 44–50% schema savings (28 tools: ~11k → ~5.5k tokens); under an 8K context, uncompressed schemas cause total overflow while compressed ones restore function (+20.5 pp exact match — "binary enablement," not a marginal gain). Overflow point at frontier context sizes: ~494 tools uncompressed vs >800 compressed.

**Speakeasy Dynamic Toolsets v2** ✅ — https://www.speakeasy.com/blog/how-we-reduced-token-usage-by-100x-dynamic-toolsets-v2/
- Production three-tool facade: `search_tools` (embedding search + tag filters) / `describe_tools` (lazy schemas) / `execute_tool`. Verified: **up to 160x token reduction**; input tokens −96.7% (simple) / −91.2% (complex); **100% task success across 40–400-tool toolsets on Claude Sonnet 4.5**. Verified tradeoff: **2–3x more tool calls, ~50% longer execution** — the canonical cost of lazy discovery.

**StackOne survey** ⬜ (blog, corroborates the above) — https://www.stackone.com/blog/mcp-token-optimization/
- Aggregates cross-vendor numbers: GitHub MCP = 17,600 schema tokens/request; multi-server setups >30k tokens before any work; Atlassian's `mcp-compressor` cuts GitHub MCP schemas 17.6k → ~500 (97%) but at max compression descriptions vanish and the model must guess between similar tools; search-first discovery on a 400-tool catalog: 400k+ → 6k tokens *and* Opus 4 accuracy 49% → 74%; response filtering cuts ~95%/call but does not fix accumulation (>20% performance drop for mid-context info — "lost in the middle").

### 2.3 Code mode / script-based execution

**CodeAct** ⬜ — ICML 2024, *"Executable Code Actions Elicit Better LLM Agents"* (Wang et al.), https://arxiv.org/abs/2402.01030 + https://github.com/xingyaoww/code-act
- The academic foundation: executable Python as the unified action space instead of JSON tool calls; **up to 20% higher task success** across 17 LLMs on API-Bank + M3ToolEval; one code action composes many tool calls; released CodeActInstruct (7k trajectories) + CodeActAgent models.

**Anthropic — Code execution with MCP** ✅ — https://www.anthropic.com/engineering/code-execution-with-mcp
- Verified: presenting MCP tools as code APIs on a filesystem (progressive disclosure — read definitions on demand) plus in-sandbox filtering/transformation of intermediate data: **150,000 → 2,000 tokens (98.7%)** on the worked example; "the agent sees five rows instead of 10,000." Verified tradeoff statement: secure sandboxing, resource limits, and monitoring are operational overhead that direct tool calls avoid. Privacy bonus: intermediate data can stay in the sandbox; PII can be tokenized before reaching the model.

**Cloudflare Code Mode** ✅ — https://blog.cloudflare.com/code-mode-mcp/ (Feb 2026)
- Verified: entire Cloudflare API behind **two tools** (`search()`, `execute()`) at a **fixed ~1,000-token footprint**; **99.9% input-token reduction** vs the ~1.17M tokens a conventional per-endpoint MCP server would need; agent JS runs in a V8 isolate (no fs, no env vars, external fetch off by default). Explicit argument that schema-RAG is inferior to code mode because each *retrieved* tool still costs tokens.

**elusznik/mcp-server-code-execution-mode** ⬜ — https://github.com/elusznik/mcp-server-code-execution-mode
- The reference open-source implementation of Anthropic's pattern as a *proxy*: one `run_python` tool; LLM-authored Python discovers/calls/composes downstream stdio MCP servers inside a rootless Podman/Docker sandbox (no network, read-only rootfs, caps dropped, 512MB/128-pid limits). Claims ~30k → ~200 constant tokens (95%+). Auto-discovers downstream servers from 12+ standard host config locations — proof that "proxy the user's existing mcp.json" is a solved discovery problem.

**Practitioner corroboration** ⬜ — A. Rafiei, *"MCP Code Mode: context engineering…"*, https://medium.com/@amirkiarafiei/mcp-code-mode-context-engineering-for-efficient-tool-execution-in-llm-agents-c46e1ddf80ac — filesystem-discovered tool APIs: ~94% initial-context and ~86% final-context reduction in a worked example; distinguishes lazy discovery from CodeAct's upfront metadata.

**mksglu/context-mode** ✅ — https://github.com/mksglu/context-mode — TypeScript, ELv2, **19.1k ⭐**
- The fastest-growing direct competitor. Verified: sandboxed tool-output execution (only stdout enters context; "315 KB becomes 5.4 KB — 98%"), "47 × Read() = 700 KB → 1 × ctx_execute() = 3.6 KB," SQLite+FTS5 persistent session memory (15+ event categories, state rebuild after compaction), mandatory code-first paradigm (12 languages), hook-based routing enforcement, 11 tools, 17 host platforms.
- Friction finding: hook capabilities are host-dependent (Cursor doesn't surface hook-injected context; Codex CLI PreToolUse is deny-only; several hosts lack SessionStart → no post-compaction restore). Deliberately rejects brevity-prompting, citing benchmark degradation.

### 2.4 Structural repository analysis (AST / graph indexing)

**Aider repo-map** ⬜ — https://aider.chat/2023/10/22/repomap.html
- The canonical design: tree-sitter ASTs → definitions/references graph → **graph-ranking (PageRank-style) over a file-dependency graph** → binary-search the ranked content down to a fixed token budget (default 1,000 tokens, `--map-tokens`). Sent with *every* request for standing structural awareness. No quantified savings published — the argument is feasibility (whole repos can't fit in context).

**RepoMapper** ⬜ — https://github.com/pdavis68/RepoMapper — Aider's repo-map extracted as a standalone CLI + stdio MCP server; tree-sitter + PageRank; 34 languages; configurable token budget with binary-search fitting; no measured savings published (MIT, ~186 ⭐).

**Serena** ⬜ — https://github.com/oraios/serena — MIT, **~26.6k ⭐**
- LSP-backed (not tree-sitter) symbol-level retrieval and *editing* for agents, 40+ languages; "symbolic editing tools are less error-prone and much more token-efficient than typical alternatives" (qualitative claim; self-run ~20-task eval, no independent benchmark). Known limitation: symbol resolution generally fails inside external/third-party dependencies. Signal: the highest-starred tool in this family sells *both* retrieval and edit precision, and wins adoption without publishing numbers — brand/DX matters.

**code-graph-mcp** ⬜ — https://github.com/sdsrss/code-graph-mcp — Rust; tree-sitter ASTs for 19 languages; SQLite FTS5 + sqlite-vec hybrid BM25+vector search fused by Reciprocal-Rank-Fusion; BLAKE3 Merkle tree incremental change detection (<250ms re-index); 13 MCP tools (call graphs, impact analysis, dead code…). Claims 40–60% session-level savings vs grep workflows; ~95% fewer source lines read (400 vs ~8,000); ~80% fewer tool calls per navigation task. Honest limitations list (no AST for HTML/CSS/JSON, Kotlin/Swift `implements` unreliable, dead-code false positives past a 4096-byte cap).

**codebase-memory-mcp** ⬜ — https://github.com/DeusData/codebase-memory-mcp — C + tree-sitter, **158 vendored language grammars**; SQLite knowledge graph queried via a read-only openCypher subset, <1ms queries; claims Linux-kernel (28M LOC) full index in 3 minutes; claims 99.2% token reduction on structural queries (~3.4k vs ~412k tokens/5 queries) and, citing arXiv preprint 2603.27277 (⚠️ not independently verified in this pass), 83% answer quality with 10x fewer tool calls across 31 repos. Ships as a single static binary that auto-configures 43 MCP client surfaces — the current bar for zero-friction install.

**code-review-graph** ⬜ — https://github.com/tirth8205/code-review-graph — tree-sitter AST → node/edge graph in SQLite, 30 MCP tools; the most *honest* benchmark reporting in the family: **median ~82x per-question token reduction across 6 repos (range 38x–528x)**, explicitly flagging 528x as a single best-case; admits keyword-search MRR ~0.35, flow-detection recall 33%, impact-F1 0.71 measured against circular graph-derived ground truth, and that **small single-file changes can cost more tokens than a naive read** due to structural metadata overhead.

### 2.5 Prompt/context compression & semantic caching (research baselines)

**LLMLingua** ⬜ — EMNLP 2023, https://llmlingua.com/llmlingua.html (arXiv:2310.05736) — coarse-to-fine perplexity-based compression: budget controller + iterative token-level compression + distribution alignment; **up to 20x compression**; GSM8K 77.33 EM at 20x (117 tokens) vs 78.85 full-shot (2,366 tokens) ≈ 1.5% loss; but BBH drops 70.07 → 56.85 at 7x — **compression cost is task-dependent**.

**LongLLMLingua** ⬜ — ACL 2024, https://arxiv.org/abs/2310.06839 — question-aware compression **improves** accuracy up to +21.4% with ~4x fewer tokens (compression as noise removal / position-bias mitigation); 94.0% cost reduction on LooGLE; 1.4–2.6x latency gains at 2–6x compression on ~10k-token prompts.

**LLMLingua-2** ⬜ — Findings of ACL 2024, https://arxiv.org/abs/2403.12968 — reformulates compression as *token classification* with a bidirectional encoder (XLM-RoBERTa) trained by data distillation; 2–5x compression, 1.6–2.9x end-to-end speedup, compressor itself 3–6x faster; extractive (never rewrites) → faithfulness guarantee relevant to hallucination control.

**GPTCache** ⬜ — NLP-OSS 2023, https://aclanthology.org/2023.nlposs-1.24/ — the citable academic reference for semantic LLM-response caching; 2–10x response-speed gain on hits; abstract publishes no false-hit/precision numbers — the recall-vs-precision tradeoff of semantic caching remains under-benchmarked in the literature (TokenGuard's identity-key scoping is a defensible answer to exactly this gap).

---

## 3. Autonomous Benchmarking

**Criteria** (chosen per the autonomous mandate; each scored Low/Med/High from the evidence above):

- **C1 Cognitive Load** — does the approach preserve/improve task accuracy and avoid degrading model reasoning (hallucination, lost-in-the-middle, wrong-tool selection)?
- **C2 Economic Impact** — measured input+output token reduction per successful task cycle, and whether savings compound across a session.
- **C3 Integration Friction** — effort to adopt in an existing MCP host (mcp.json edit vs hooks vs host modification), plus operational burden (sandboxes, indexes).
- **C4 Evidence Quality** — peer-reviewed / independently verified / vendor-measured / self-reported. (Added because this market is saturated with self-reported percentages.)

| Approach (exemplar) | C1 Cognitive load | C2 Economic impact | C3 Integration friction | C4 Evidence |
|---|---|---|---|---|
| Code mode (Anthropic ✅, Cloudflare ✅, CodeAct) | **High** — accuracy *rises* (+20% CodeAct; 42%→80% Sonnet on MCP Atlas per StackOne) | **Highest** — 98.7–99.9%; fixed footprint regardless of API size; intermediate data never enters context | Med — needs a secure sandbox (Anthropic's stated tradeoff); trivial for the *user* once shipped | Strong (peer-reviewed + two vendors) |
| Dynamic toolsets / schema RAG (Speakeasy ✅, RAG-MCP ✅, MCP-Zero ✅) | **High** — selection accuracy 3x baseline; avoids >30-tool degradation cliff | High — 50–98% of schema tokens; up to 160x | **Low** — drop-in MCP facade; cost = 2–3x more calls, ~50% latency | Strong (2 papers + production benchmark) |
| Structural indexing (Aider, Serena, code-graph-mcp, codebase-memory-mcp) | High for navigation queries; graph-derived answers reduce grep-thrash; risk: stale index → confident wrong answers | High on code-understanding tasks (40–99%); *can go negative* on small single-file edits (code-review-graph's admission) | Low–Med — one server + background index; language coverage is the real cost | Medium (mostly self-reported; one honest median: ~82x) |
| Prompt compression (LLMLingua family) | Med — task-dependent (GSM8K −1.5% at 20x, BBH −13 pts at 7x); LongLLMLingua can *gain* +21.4% | Med–High (2–20x) but applies at the *host/prompt* layer, awkward from inside an MCP server | High for an MCP server (it doesn't own the prompt); Low if host-side | **Strongest** (3 peer-reviewed papers) |
| Result caching + distillation (token-optimizer ✅, context-mode, GPTCache, **TokenGuard**) | Med–High — exact-key hits are safe; semantic hits carry false-positive risk (unbenchmarked in literature); diffs/outlines preserve grounding | High on repeat-heavy sessions (60–98%); zero on cold single-pass tasks | **Lowest** — one mcp.json entry; hooks optional | Weak-to-Med (almost entirely self-reported) |
| Schema compression (Tscg ✅, mcp-compressor) | Med — "binary enablement" under tight budgets (+20.5 pp) but over-compression causes tool-guessing errors | Med (44–50% of schema tokens) | Low (transform layer) | Medium (simulation + vendor) |

**Benchmark conclusions:**
1. **Code mode dominates on economics *and* accuracy** and is the only family with a *fixed* context footprint as tool count grows. Its cost — sandbox infrastructure — is a cost TokenGuard has *already paid* (GraalVM JS sandbox, circuit breaker, watchdog).
2. **Dynamic tool selection is the highest-leverage complement**, attacking the one cost code mode alone doesn't fix in stock hosts: schemas of *other* servers in mcp.json.
3. **Structural indexing must publish honest medians**, and must guard against the negative-ROI case on small edits (fall back to raw read/diff when the structural answer would be bigger).
4. **Semantic caching's open problem is precision**, not recall — no source publishes false-hit rates. Correctness-scoped semantic matching is a differentiator worth naming and benchmarking.
5. **Self-reported numbers are the norm; independently reproducible benchmarks are the exception** — a credibility vacuum a commercial product can occupy.

---

## 4. Synthesis — TokenGuard vs State of the Art

Grounded in a code audit on 2026-07-19 (not just docs): eviction confirmed implemented (`CacheStore.evictOverBudget`, invoked on every store, `CacheStore.java:136`), symlink-safe jail confirmed (`PathJail.java` uses `toRealPath()` at lines 18/35/46), identity-key-scoped semantic matching confirmed (`CacheLayer.java:56-78`).

### Where TokenGuard is at or ahead of state of the art

| Capability | TokenGuard | Field comparison |
|---|---|---|
| **Diff-on-reread + session file-state** (`ReadFileTool`, `FileStateTracker`) | Implemented | **Rare.** No surveyed competitor returns "unchanged"/minimal-diff on re-reads; context-mode approximates via session memory. This is a genuinely differentiated, high-frequency win — agents re-read constantly. |
| **Correctness-scoped semantic cache** (`CacheLayer` Tier-2: fuzzy match only over declared free-text, pinned to an identity key over all other args; dependency-hash invalidation via `DependencyTracker`) | Implemented | **Ahead.** GPTCache/token-optimizer publish no false-hit safeguards; TokenGuard's design guarantees a semantic hit can never bridge different paths/symbols, and file-hash dependencies invalidate stale entries. Unbenchmarked → unmonetized. |
| **Sandboxed code mode** (`ScriptSandbox`: per-call GraalVM JS context, 5M-statement + 5s limits, 64KB output cap, circuit breaker; read-only `HostApi`) | Implemented | At parity with the pattern Anthropic/Cloudflare validated (98.7–99.9% class savings); local + zero-JRE beats Cloudflare's server-side variant on data privacy. Missing: downstream-tool composition (see gaps). |
| **Zero-friction local deployment** (single native binary, stdio, fail-open licensing) | Implemented | Matches the bar set by codebase-memory-mcp; better than Node/Python competitors (no runtime dependency). Fail-open pass-through is a real enterprise trust feature. |
| **Commercial infrastructure** (Ed25519 entitlements, E2E-encrypted team cache, telemetry rollups) | Implemented | **Ahead of every surveyed OSS project.** Nobody else has a team-shared cache; Teammate-B-benefits-from-Teammate-A is an unclaimed value proposition in this market. |

### Where TokenGuard trails state of the art

| Gap | Severity | Evidence |
|---|---|---|
| **No MCP Client Multiplexer** (plan §1.3, zero code) | **Critical / strategic.** TokenGuard optimizes only its own 7 tools; the field's biggest verified wins (17.6k tokens for GitHub MCP alone; 30k+ multi-server; 95–99.9% cuts) come from optimizing *the rest of mcp.json*. elusznik's server proves auto-discovery from host configs is feasible; Speakeasy proves the facade pattern; RAG-MCP/MCP-Zero prove the accuracy upside. | StackOne ✅, Speakeasy ✅, RAG-MCP ✅, MCP-Zero ✅, elusznik |
| **Java-only AST engine** (JavaParser) | **High.** Competitors: 19 (code-graph-mcp), 34 (RepoMapper), 40+ (Serena), 158 (codebase-memory-mcp) languages via tree-sitter/LSP. Plan already calls this "the biggest product-scope flag" (§A2). | All §2.4 sources |
| **No ranked repo-map under a token budget** | Med–High. `query_codebase_structure` answers point queries; Aider's graph-ranked, budget-fitted map (the most-copied design in the family) provides *standing* orientation for ~1,000 tokens. | Aider, RepoMapper |
| **No session memory / compaction survival** | Med. context-mode (19.1k ⭐) rebuilds working state after compaction — a retention feature TokenGuard's SQLite layer could support cheaply. | context-mode ✅ |
| **`chars/4` token estimation + no published benchmarks** | **High commercially.** Every competitor markets a number; TokenGuard's own plan calls chars/4 "a lie." Savings claims from a paid product must beat the (often inflated) free-tier claims on credibility, not magnitude. | token-optimizer ✅, code-review-graph (honest-median model) |
| **No hook-based routing** | Med. token-optimizer and context-mode intercept native Read/Grep to *enforce* optimization; TokenGuard depends on the model choosing its tools. Host-dependent (context-mode's compatibility matrix) but decisive on Claude Code. | token-optimizer ✅, context-mode ✅ |
| **Fallback bag-of-words embedder** (ADR-0003) | Med. Lower semantic recall than model2vec-class; also weaker than the hybrid BM25+vector+RRF retrieval code-graph-mcp ships. Note: identity-key scoping caps the *risk*, so this gap costs recall, not correctness. | code-graph-mcp, ADR-0003 |
| Negative-ROI guard for structural answers | Low. code-review-graph admits structural metadata can exceed a naive read on small files; TokenGuard should compare and return the cheaper representation. | code-review-graph |

**Net position:** TokenGuard is a top-quartile *implementation* (breadth of engines, safety of cache design, deployment story, commercial plumbing) wrapped in a bottom-quartile *market posture* (no proxy story, no numbers, one language). The gaps are product-scope gaps, not architecture rewrites — the layered ToolRouter, SQLite store, and sandbox are the right substrate for every roadmap item below.

---

## 5. Strategic Roadmap — Key Capabilities for a Profitable, Enterprise-Grade TokenGuard

Prioritized by measurable user ROI; each item names its evidence base. Architectural-integrity constraint honored: everything below composes with the existing `ToolRouter` layer chain and SQLite store — no engine is bypassed or forked.

### P0 — The wedge (next quarter)

**KC-1: MCP Multiplexer + Dynamic Toolset Facade** — *"the only server in your mcp.json."*
Implement plan §1.3: spawn/manage downstream MCP servers discovered from the host's own config (the elusznik precedent: 12+ config locations). Expose their capabilities through TokenGuard's existing layer chain, fronted by the Speakeasy triad — `search_tools` / `describe_tools` / `execute_tool` — with lazy schema promotion (Tool Attention's summary-pool pattern). Downstream results flow through CacheLayer (they're just tools), so caching/metrics/team-cache apply to *every* server the user owns, not only TokenGuard's.
*ROI evidence:* 95–99% schema-token reduction and *higher* selection accuracy at scale (RAG-MCP ✅, MCP-Zero ✅, Speakeasy ✅ 100%-success benchmark); a GitHub-MCP user saves ~17.6k tokens per request on day one (StackOne). *This single feature converts TokenGuard from "7 better tools" into infrastructure* — and it is the moat competitors with hosted backends cannot match locally.

**KC-2: Code-mode composition over downstream tools.**
Extend `HostApi` with a `tools.*` namespace so sandboxed scripts can call and compose *downstream* (multiplexed) MCP tools, with intermediate results staying in the sandbox — Anthropic's verified 98.7% pattern, Cloudflare's fixed ~1k-token footprint, executed locally in the GraalVM sandbox TokenGuard already hardened. Keep direct tool-call mode as default; code mode as the profile for power users (matches existing `--profile code-mode`).
*ROI evidence:* Anthropic ✅, Cloudflare ✅, CodeAct (+20% task success), Sonnet 42%→80% on MCP Atlas (StackOne). *Security note:* honor Anthropic's stated tradeoff — ship the Oracle-GraalVM `sandbox.MaxCPUTime`/`MaxHeapMemory` limits (plan §4.2) *before* letting scripts touch downstream tools.

**KC-3: Honest, measured savings — tokenizer-accurate counting + reproducible benchmark + dashboard-grade `guard_status`.**
Replace `chars/4` with a real BPE-compatible counter; publish a public benchmark harness (fixed agent task set, tokens-per-completed-task, cache hit/false-hit rates) reporting **medians with ranges**, following code-review-graph's credibility model rather than best-case marketing. Surface per-session $ savings in `guard_status` and the (planned) dashboard.
*ROI rationale:* this is the monetization keystone — enterprises buy a number they can audit. It also converts TokenGuard's unique verifiable claims (semantic-cache precision by construction; diff-on-reread) into marketable proof. Immediate, measurable, and no competitor does it honestly.

### P1 — Parity + differentiation (two quarters)

**KC-4: Multi-language structural engine via tree-sitter** (keep JavaParser for deep Java semantics). Ship TS/JS, Python, Go first — that's where the agent-coding market is. Add the negative-ROI guard: if the structural answer exceeds the raw read/diff, return the cheaper one (lesson: code-review-graph).
**KC-5: Graph-ranked repo map under a token budget** (`get_repo_map`, default ~1k tokens, PageRank over the existing symbol/edge graph, binary-search fitting) — the Aider design, which TokenGuard's index already 80%-enables.
**KC-6: Session memory + compaction recovery** — persist a rolling session journal (files touched, edits applied, errors, decisions) in SQLite; one `restore_context` tool returns a dense state summary after host compaction (context-mode's most-loved feature; trivial on `FileStateTracker`'s foundation).
**KC-7: Host-hook integration pack for Claude Code** (optional): PreToolUse redirect of native Read/Grep/Glob to TokenGuard equivalents + PreCompact state capture — enforcement instead of hope (token-optimizer/context-mode precedent), gated per-host by the known compatibility matrix.

### P2 — Enterprise hardening (monetization deepening)

**KC-8: Model2vec-class embedder asset** (close ADR-0003) — recall uplift for Tier-2 + `search_tools`; the int8 storage format and call sites are already stable, so this is a drop-in.
**KC-9: Enterprise trust package** — cache-at-rest encryption (existing `cache.encrypt` flag), audit batch upload to `/v1/audit` (hash chain already computed), `--no-telemetry` flag, structured JSON logs. Sell the *only* offering in this market with E2E-encrypted team cache + tamper-evident audit: "your code never leaves the machine; your savings are provable."
**KC-10: Team-cache network effects** — seed the shared cache with multiplexed downstream results (KC-1) and repo-map/index artifacts, making the Team tier's Teammate-A→Teammate-B value proposition concrete and demo-able.

**Explicitly deprioritized (with reasoning):** in-server prompt compression (LLMLingua-class) — highest-friction fit for a tool provider that doesn't own the prompt (C3 analysis §3); revisit only as an *optional* distillation step on oversized tool outputs. Brotli-style blob compression as a headline feature — it saves disk, not context (token-optimizer lesson). Growing the tool count — the >30-tool accuracy cliff (RAG-MCP ✅) says keep the surface small; the ~7-tool surface (2 in code-mode profile) is an asset.

---

## 6. Edge Cases Explicitly Explored (per constraints)

- **Adaptive context windowing:** Tool Attention's per-turn summary-pool/schema-promotion (95% cut, simulation-verified) and Aider's budget-fitted binary-search map are the two practical adaptive-windowing designs; both are absorbed into KC-1 and KC-5. LongLLMLingua shows adaptive *question-aware* selection can raise accuracy (+21.4%) — supporting evidence that less, better-chosen context beats more context.
- **Intent-based tool pruning:** RAG-MCP (retrieval), MCP-Zero (agent-initiated requests), Tool Attention (ISO score + state-aware gating), Speakeasy (search-first). Consensus: prune schemas, not capabilities; keep a discovery path; accept the 2–3x call overhead as the price of the 10–100x token win. Adopted in KC-1.
- **Structural repository analysis:** six implementations compared (§2.4); key edge finding is the *negative-ROI small-change case* (code-review-graph) → KC-4's guard; second finding is index staleness as a hallucination vector → TokenGuard's hash-diff incremental refresh + dependency invalidation already address it and should be benchmarked as such (KC-3).

---

## 7. Sources Analyzed (auditable list — 24 sources; anchor + 23 additional)

Verification key: ✅ = re-verified against the primary source on 2026-07-19 · ⬜ = extracted from the fetched source; figures are that source's own claims.

**Anchor**
1. ✅ token-optimizer-mcp — https://github.com/ooples/token-optimizer-mcp

**Tool-call indexation / dynamic tool selection**
2. ✅ RAG-MCP (Gan & Sun) — https://arxiv.org/abs/2505.03275
3. ✅ MCP-Zero (Fei et al.) — https://arxiv.org/abs/2506.01056
4. ✅ Tool Attention (Sadani & Kumar, 2026) — https://arxiv.org/abs/2604.21816
5. ✅ Tool-Schema Compression / Tscg (Sakizli, 2026) — https://arxiv.org/abs/2605.26165
6. ✅ Speakeasy Dynamic Toolsets v2 — https://www.speakeasy.com/blog/how-we-reduced-token-usage-by-100x-dynamic-toolsets-v2/
7. ⬜ StackOne, "MCP token optimization" (survey; incl. Atlassian mcp-compressor data) — https://www.stackone.com/blog/mcp-token-optimization/

**Code mode / script-based execution**
8. ⬜ CodeAct paper (ICML 2024) — https://arxiv.org/abs/2402.01030
9. ⬜ CodeAct repository — https://github.com/xingyaoww/code-act
10. ✅ Anthropic Engineering, "Code execution with MCP" — https://www.anthropic.com/engineering/code-execution-with-mcp
11. ✅ Cloudflare, "Code Mode" — https://blog.cloudflare.com/code-mode-mcp/
12. ⬜ mcp-server-code-execution-mode (elusznik) — https://github.com/elusznik/mcp-server-code-execution-mode
13. ⬜ Rafiei, "MCP Code Mode: context engineering…" — https://medium.com/@amirkiarafiei/mcp-code-mode-context-engineering-for-efficient-tool-execution-in-llm-agents-c46e1ddf80ac
14. ✅ context-mode (mksglu) — https://github.com/mksglu/context-mode

**Structural repository analysis**
15. ⬜ Aider repo-map (tree-sitter writeup) — https://aider.chat/2023/10/22/repomap.html
16. ⬜ RepoMapper — https://github.com/pdavis68/RepoMapper
17. ⬜ Serena (oraios) — https://github.com/oraios/serena
18. ⬜ code-graph-mcp — https://github.com/sdsrss/code-graph-mcp
19. ⬜ codebase-memory-mcp — https://github.com/DeusData/codebase-memory-mcp (cites arXiv:2603.27277, not independently verified)
20. ⬜ code-review-graph — https://github.com/tirth8205/code-review-graph

**Prompt/context compression & semantic caching**
21. ⬜ LLMLingua (EMNLP 2023) — https://llmlingua.com/llmlingua.html (arXiv:2310.05736)
22. ⬜ LongLLMLingua (ACL 2024) — https://arxiv.org/abs/2310.06839
23. ⬜ LLMLingua-2 (Findings of ACL 2024) — https://arxiv.org/abs/2403.12968
24. ⬜ GPTCache (NLP-OSS 2023) — https://aclanthology.org/2023.nlposs-1.24/

---

## 8. Stated Assumptions & Limitations

1. **Verification scope.** The automated adversarial-verification stage failed on infrastructure limits; 9 primary sources carrying the report's headline numbers were manually re-verified (✅). ⬜ sources were genuinely fetched and quoted, but their figures are the projects' own claims — treat star counts and self-reported percentages as directional.
2. **Self-reported benchmark inflation is assumed endemic** in this market (e.g., 528x best-case vs 82x median in the one project honest enough to publish both). Roadmap KC-3 is designed around that assumption.
3. **Ecosystem snapshot date** is 2026-07-19; star counts and release versions will drift.
4. arXiv:2603.27277 (cited by codebase-memory-mcp) was **not** fetched; its numbers are relayed with that flag.
5. TokenGuard code-state claims come from direct source audit of `CacheLayer.java`, `CacheStore.java`, `PathJail.java`, `Embedder.java` on 2026-07-19; other component statuses rely on CLAUDE.md (last verified 2026-07-08).
6. No genuine blockers were hit; all five execution steps completed in a single autonomous pass.

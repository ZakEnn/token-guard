# TokenGuard Benchmark Results (P2.1)

Reproduce: `mvn -pl bench -am verify` (RESULTS.md is regenerated; content is
deterministic — reruns must be byte-identical).

**Counting:** exact BPE (cl100k_base (exact BPE)). **Policy:** medians with ranges over all samples —
never best-case numbers (research_proposition.md KC-3).

## Methodology
Deterministic fixture workspace (8 generated Java files, 6–34 methods each) and a
deterministic fake downstream MCP server (~2 KB payloads). Naive baselines are
conservative: reads return full content, edit acks are 3 tokens (not full-file
echoes), navigation baseline reads only the single file that contains the target
(not the whole tree), composition baseline pays only the two raw tool outputs.

| Task | n | naive median (tokens) | TokenGuard median (tokens) | reduction median (range) |
|---|---|---|---|---|
| T1 re-read-heavy editing (read, edit, verify, re-read) | 8 | 2397 | 1167 | **51.2%** (36.6–54.1%) |
| T2 navigation (locate + ranged read vs full read) | 8 | 798 | 140 | **82.3%** (44.4–89.6%) |
| T3 structural query (outline vs full read) | 8 | 798 | 471 | **41.0%** (35.3–42.0%) |
| T4 downstream composition (mcp.call script vs raw outputs) | 5 | 1950 | 4 | **99.8%** (99.8–99.8%) |

## Caveats
- Fixture-workspace numbers, not field telemetry; savings scale with file size
  and session length, and T1 depends on how often the agent re-reads.
- Cache-hit savings (recomputation, not context tokens) are excluded on purpose.
- Supersedes the informal chars/4-based figures in `evals.md`.

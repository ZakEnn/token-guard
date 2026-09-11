# ADR 0004 — Multi-language structural indexing parser strategy

**Status:** PROPOSED — decision gated on the native-image spike (P3.1); do not
implement a parser before this ADR is accepted with CI evidence.

## Context

`research_proposition.md` (KC-4) makes multi-language structural indexing the
key parity gap: competitors index 19–158 languages (code-graph-mcp, RepoMapper,
Serena, codebase-memory-mcp), all via tree-sitter or LSP. TokenGuard's Engine 2
is Java-only (JavaParser). But `TOKENGUARD_PLAN.md` §2's governing constraint —
everything must survive GraalVM native-image on 5 targets, which "kills or
complicates anything with JNI" — conflicts with tree-sitter, which is a C
library bound via JNI.

## Options (to be settled by the spike)

1. **java-tree-sitter with per-platform statically linked natives.** The
   xerial sqlite-jdbc precedent proves statically-linked JNI *can* survive
   native-image (it ships GraalVM reachability metadata). Cost: per-target
   native builds of tree-sitter + each grammar; binary size; the highest-risk
   dependency class this project has (same reason ONNX was rejected in §6.1.1).
2. **Pure-Java parsers per language** (e.g. existing Java-native TS/Python
   parsers of varying quality). Cost: uneven parser quality/maintenance,
   no uniform grammar ecosystem; benefit: zero new native-image risk.
3. **LSP-backend (Serena's approach).** Spawns per-language servers as child
   processes (no JNI in-binary). Cost: external runtime dependencies on user
   machines (contradicts the zero-dependency install story); latency.

## Spike protocol (P3.1 step 1, time-boxed)

On the 5-target CI matrix: build the candidate into the native image, index a
fixture TS repo, assert outline/symbol parity with the JavaParser fixture
suite, record binary-size delta (gate: ≤ +15 MB) and cold-start delta.

## Decision

_Pending spike results._ The `astindex/QueryEngine` contract and the
`symbols`/`symbol_edges` schema are language-neutral already — whichever
option wins plugs in behind the existing interface; Java stays on JavaParser
either way.

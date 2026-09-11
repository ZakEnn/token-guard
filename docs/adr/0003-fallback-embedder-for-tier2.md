# ADR 0003 — Deterministic fallback embedder for the semantic cache tier

**Status:** accepted (M4); revisit when the A5 model asset lands

Plan §6.1.1 chose static (model2vec-class) embeddings for Tier 2. Assumption A5
("verify model licensing/procurement before M4") could not be resolved in the
build environment — no model asset can be procured/converted here.

Decision: ship the full Tier-2 machinery (embedding storage, identity-arg
guards, threshold, per-identity-scope brute-force cosine) with a deterministic
bag-of-words random-projection embedder:

- lowercased word features, each hashed to a sparse signed projection into
  256 dims, mean-pooled, L2-normalized; pure Java, zero assets, microseconds.
- word reordering / punctuation / duplication → cosine ~1.0 (hit).
- one identifier changed in a short query → large cosine drop (miss) — the
  false-positive analysis is tractable precisely because features are whole
  words, not trigrams.
- synonym-level paraphrase ("methods" vs "functions") → miss. This recall is
  deferred to the real model; the M4 plan gate is amended accordingly.

Scope guard (per plan): semantic matching applies only to declared free-text
fields (Tool.semanticText) — never scripts, paths, regexes, or symbol
identities. Threshold 0.94, config `cache.semantic_threshold`.

Swap point: `io.tokenguard.cache.Embedder` — replace the feature hasher with a
model.bin token-table lookup; storage format and call sites are unchanged.

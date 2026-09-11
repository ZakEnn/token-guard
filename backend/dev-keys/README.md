# DEV-ONLY entitlement signing key

`entitlement_ed25519_private.b64` is a **development** keypair generated at
build time. Its public half is bundled in the binary at
`core/src/main/resources/keys/entitlement_ed25519.pub`.

**Before any production release:** generate a fresh keypair, store the private
key ONLY as a Supabase Edge Function secret (`ENTITLEMENT_SIGNING_KEY`), replace
the bundled public key, and delete this directory. The private key must never
ship in the repo or the binary (plan §6.2/§4.3).

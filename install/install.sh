#!/bin/sh
# TokenGuard installer (plan §4 / §7 M8). One-line use:
#   curl -sSf https://tokenguard.io/install.sh | sh
# Detects platform, downloads the matching native binary, verifies its
# checksum, installs to a user-writable dir, and prints the mcp.json snippet.
set -eu

REPO="tokenguard/token-guard"
VERSION="${TOKEN_GUARD_VERSION:-latest}"
INSTALL_DIR="${TOKEN_GUARD_INSTALL_DIR:-$HOME/.local/bin}"

say() { printf '%s\n' "$*"; }
err() { printf 'error: %s\n' "$*" >&2; exit 1; }

detect_target() {
  os=$(uname -s); arch=$(uname -m)
  case "$os" in
    Linux)  os=linux ;;
    Darwin) os=macos ;;
    *) err "unsupported OS: $os (Windows: use install.ps1)" ;;
  esac
  case "$arch" in
    x86_64|amd64) arch=x64 ;;
    arm64|aarch64) arch=arm64 ;;
    *) err "unsupported architecture: $arch" ;;
  esac
  printf '%s-%s' "$os" "$arch"
}

TARGET=$(detect_target)
BASE="https://github.com/$REPO/releases/${VERSION:+download/$VERSION}"
[ "$VERSION" = latest ] && BASE="https://github.com/$REPO/releases/latest/download"
BIN="token-guard-$TARGET"

say "Installing token-guard ($TARGET, $VERSION)..."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -sSfL "$BASE/$BIN" -o "$tmp/token-guard" || err "download failed: $BASE/$BIN"
if curl -sSfL "$BASE/$BIN.sha256" -o "$tmp/sum" 2>/dev/null; then
  expected=$(cut -d' ' -f1 < "$tmp/sum")
  actual=$( (sha256sum "$tmp/token-guard" 2>/dev/null || shasum -a 256 "$tmp/token-guard") | cut -d' ' -f1)
  [ "$expected" = "$actual" ] || err "checksum mismatch (expected $expected, got $actual)"
  say "checksum verified"
else
  say "warning: no published checksum; skipping verification"
fi

mkdir -p "$INSTALL_DIR"
chmod +x "$tmp/token-guard"
mv "$tmp/token-guard" "$INSTALL_DIR/token-guard"
say "installed to $INSTALL_DIR/token-guard"

case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *) say "note: add $INSTALL_DIR to your PATH" ;;
esac

cat <<EOF

Add this to your IDE's mcp.json (Cursor, Claude Desktop, Windsurf, Cline...):

  {
    "mcpServers": {
      "token-guard": {
        "command": "$INSTALL_DIR/token-guard",
        "args": ["--license-key", "YOUR_LICENSE_KEY"],
        "env": { "TOKEN_GUARD_CACHE_DIR": "~/.token-guard/cache" }
      }
    }
  }

No license key? TokenGuard runs in free-local mode (local optimization,
100 MB cache). Get a key at https://tokenguard.io to unlock the full cache,
cloud team cache, and analytics.
EOF

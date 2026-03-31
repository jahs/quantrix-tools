#!/bin/bash
#
# Quantrix Tools Installer
#
# Usage:
#   curl -sL https://raw.githubusercontent.com/jahs/quantrix-tools/main/install.sh | bash
#
# Or from a local clone:
#   ./install.sh
#
# Installs:
#   - Groovy Loader Plugin (JAR) → Quantrix plugins/
#   - Quantrix Server Plugin (JAR) → Quantrix groovy-plugins/
#   - Claude Code skills → ~/.claude/skills/
#
set -e

REPO="jahs/quantrix-tools"
QX_PLUGINS="$HOME/Library/Application Support/Quantrix/plugins"
QX_GROOVY="$HOME/Library/Application Support/Quantrix/groovy-plugins"
CLAUDE_SKILLS="$HOME/.claude/skills"

# Detect whether we're running from a local clone or via curl
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}" 2>/dev/null || echo ".")" && pwd)"
if [ -d "$SCRIPT_DIR/.git" ] && [ -d "$SCRIPT_DIR/skills" ]; then
    MODE="local"
    VERSION=$(cd "$SCRIPT_DIR" && git describe --tags --match 'v*' 2>/dev/null | sed 's/^v//')
    VERSION=${VERSION:-0.0.0-dev}
else
    MODE="remote"
fi

echo "=== Quantrix Tools Installer ==="

if [ "$MODE" = "remote" ]; then
    echo "Fetching latest release..."
    RELEASE_JSON=$(curl -sL "https://api.github.com/repos/$REPO/releases/latest")
    VERSION=$(echo "$RELEASE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['tag_name'].lstrip('v'))")
    if [ -z "$VERSION" ]; then
        echo "Error: could not determine latest release. Check https://github.com/$REPO/releases"
        exit 1
    fi
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/v${VERSION}"
    SKILL_NAMES=$(echo "$RELEASE_JSON" | python3 -c "
import json, sys
assets = json.load(sys.stdin).get('assets', [])
for a in assets:
    name = a['name']
    if name.endswith('.skill'):
        print(name[:-6])
")
fi

echo "Version: $VERSION"
echo ""

# ── Install Quantrix plugins ────────────────────────────────────────

mkdir -p "$QX_PLUGINS" "$QX_GROOVY"

echo "Installing Quantrix plugins..."

# Stage builds/downloads in a temp dir so existing installs survive failures
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

# Loader plugin — build/download to staging
if [ "$MODE" = "local" ]; then
    echo "  Building loader plugin..."
    (cd "$SCRIPT_DIR/groovy-loader-plugin" && ./build.sh) >/dev/null
    LOADER_JAR=$(ls "$SCRIPT_DIR/groovy-loader-plugin/dist"/groovy-loader-plugin-*.jar 2>/dev/null | head -1)
    cp "$LOADER_JAR" "$STAGE/"
else
    curl -sfL "$DOWNLOAD_URL/groovy-loader-plugin-${VERSION}.jar" -o "$STAGE/groovy-loader-plugin-${VERSION}.jar"
fi

# Server plugin — build/download to staging
if [ "$MODE" = "local" ]; then
    echo "  Building server plugin..."
    (cd "$SCRIPT_DIR/groovy-server-plugin" && ./build.sh) >/dev/null
    SERVER_JAR=$(ls "$SCRIPT_DIR/groovy-server-plugin/dist"/quantrix-server-*.jar 2>/dev/null | head -1)
    cp "$SERVER_JAR" "$STAGE/"
else
    curl -sfL "$DOWNLOAD_URL/quantrix-server-${VERSION}.jar" -o "$STAGE/quantrix-server-${VERSION}.jar"
fi

# All builds/downloads succeeded — swap into place
rm -f "$QX_PLUGINS"/groovy-loader-plugin-*.jar
cp "$STAGE"/groovy-loader-plugin-*.jar "$QX_PLUGINS/"
echo "  $QX_PLUGINS/groovy-loader-plugin-${VERSION}.jar"

rm -f "$QX_GROOVY"/quantrix-server-*.jar
rm -rf "$QX_GROOVY/quantrix-server"
cp "$STAGE"/quantrix-server-*.jar "$QX_GROOVY/"
echo "  $QX_GROOVY/quantrix-server-${VERSION}.jar"

# ── Install Claude Code skills ──────────────────────────────────────

echo ""
echo "Installing Claude Code skills..."
mkdir -p "$CLAUDE_SKILLS"

if [ "$MODE" = "local" ]; then
    for skill_dir in "$SCRIPT_DIR"/skills/*/; do
        [ -f "$skill_dir/SKILL.md" ] || continue
        skill_name=$(basename "$skill_dir")
        rm -rf "$CLAUDE_SKILLS/$skill_name"
        cp -r "$skill_dir" "$CLAUDE_SKILLS/$skill_name"
        find "$CLAUDE_SKILLS/$skill_name" -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
        echo "  $skill_name"
    done
else
    for skill_name in $SKILL_NAMES; do
        rm -rf "$CLAUDE_SKILLS/$skill_name"
        echo -n "  $skill_name... "
        curl -sfL "$DOWNLOAD_URL/${skill_name}.skill" -o "/tmp/${skill_name}.skill"
        unzip -qo "/tmp/${skill_name}.skill" -d "$CLAUDE_SKILLS/"
        rm -f "/tmp/${skill_name}.skill"
        echo "ok"
    done
fi

# ── Done ────────────────────────────────────────────────────────────

echo ""
echo "Done."
echo "  Quantrix: restart to pick up plugin changes"
echo "  Claude:   skills are available now in all projects"

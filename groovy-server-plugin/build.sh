#!/bin/bash
set -e

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$PLUGIN_DIR/.." && pwd)"
SRC_DIR="$PLUGIN_DIR/src"
TEST_DIR="$PLUGIN_DIR/tests"
BUILD_DIR="$PLUGIN_DIR/build"
DIST_DIR="$PLUGIN_DIR/dist"
LIB_DIR="$PLUGIN_DIR/quantrix-server/lib"
VERSION=$(git describe --tags --match 'v*' 2>/dev/null | sed 's/^v//')
VERSION=${VERSION:-0.0.0-dev}

echo "=== Groovy Server Plugin Build ==="
echo "Version: $VERSION"
echo ""

rm -rf "$BUILD_DIR" "$DIST_DIR" "$LIB_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/test-classes" "$LIB_DIR" "$DIST_DIR"

# ── 1. Build pipe-preprocessor JAR ──────────────────────────────────

echo "Compiling pipe-preprocessor..."
find "$SRC_DIR" -name "*.java" -exec printf '"%s"\n' {} \; > "$BUILD_DIR/sources.txt"
javac \
    --release 11 \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

(cd "$BUILD_DIR/classes" \
    && printf "Manifest-Version: 1.0\nImplementation-Title: pipe-preprocessor\nImplementation-Version: %s\n" "$VERSION" > MANIFEST.MF \
    && jar cfm "$LIB_DIR/pipe-preprocessor-${VERSION}.jar" MANIFEST.MF . \
    && rm MANIFEST.MF)

echo "Built: quantrix-server/lib/pipe-preprocessor-${VERSION}.jar"

# ── 2. Run tests ────────────────────────────────────────────────────

echo ""
echo "Compiling tests..."
find "$TEST_DIR" -name "*.java" -exec printf '"%s"\n' {} \; > "$BUILD_DIR/test-sources.txt"
javac \
    --release 11 \
    -cp "$BUILD_DIR/classes" \
    -d "$BUILD_DIR/test-classes" \
    @"$BUILD_DIR/test-sources.txt"

echo "Running tests..."
echo ""
java \
    -cp "$BUILD_DIR/classes:$BUILD_DIR/test-classes" \
    net.jahs.quantrix.preprocessor.SelectionPreprocessorTest \
    "$TEST_DIR/tests.xml"

# ── 3. Package distributable plugin JAR ─────────────────────────────
#
# This JAR can be dropped directly into Quantrix's groovy-plugins/ dir.
# Layout:
#   quantrix-server.groovy    (entry point)
#   qx-docs.groovy            (helper — API introspection)
#   pipe-preprocessor-*.jar   (dependency)

echo ""
echo "Packaging plugin JAR..."
PLUGIN_JAR="$DIST_DIR/quantrix-server-${VERSION}.jar"
mkdir -p "$BUILD_DIR/plugin-jar"
cp "$PLUGIN_DIR/quantrix-server/quantrix-server.groovy" "$BUILD_DIR/plugin-jar/"
cp "$PLUGIN_DIR/quantrix-server/qx-docs.groovy" "$BUILD_DIR/plugin-jar/"
cp "$LIB_DIR"/*.jar "$BUILD_DIR/plugin-jar/"
printf "Manifest-Version: 1.0\nImplementation-Title: quantrix-server\nImplementation-Version: %s\n" "$VERSION" > "$BUILD_DIR/plugin-manifest.mf"
(cd "$BUILD_DIR/plugin-jar" && jar cfm "$PLUGIN_JAR" "$BUILD_DIR/plugin-manifest.mf" .)

echo "Built: dist/quantrix-server-${VERSION}.jar"
echo "Size:  $(du -h "$PLUGIN_JAR" | cut -f1)"

#!/bin/bash
set -e

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$PLUGIN_DIR/.." && pwd)"
SRC_DIR="$PLUGIN_DIR/src/main/java"
RES_DIR="$PLUGIN_DIR/src/main/resources"
BUILD_DIR="$PLUGIN_DIR/build"
DIST_DIR="$PLUGIN_DIR/dist"
STUBS_DIR="$REPO_DIR/stubs"
VERSION=$(git describe --tags --match 'v*' 2>/dev/null | sed 's/^v//')
VERSION=${VERSION:-0.0.0-dev}
JAR_NAME="groovy-loader-plugin-${VERSION}.jar"

QX_APP="/Applications/Quantrix Modeler.app/Contents/java/app"
GROOVY_VERSION="4.0.24"
GROOVY_URL="https://repo1.maven.org/maven2/org/apache/groovy/groovy/${GROOVY_VERSION}/groovy-${GROOVY_VERSION}.jar"
DEPS_DIR="$BUILD_DIR/deps"

echo "=== Groovy Loader Plugin Build ==="
echo "Version: $VERSION"
echo ""

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/stubs" "$DEPS_DIR" "$DIST_DIR"

# Build classpath — prefer Quantrix install, fall back to stubs + Maven Groovy
CP=""
if [ -d "$QX_APP" ]; then
    echo "Using Quantrix application JARs from $QX_APP"
    for jar in "$QX_APP"/*.jar "$QX_APP"/lib/*.jar; do
        [ -f "$jar" ] || continue
        [ -n "$CP" ] && CP="$CP:"
        CP="$CP$jar"
    done
else
    echo "Quantrix not installed — using stubs + Groovy from Maven Central"

    # Compile stubs
    echo "Compiling stubs..."
    find "$STUBS_DIR" -name "*.java" -exec printf '"%s"\n' {} \; > "$BUILD_DIR/stub-sources.txt"
    javac --release 11 -d "$BUILD_DIR/stubs" @"$BUILD_DIR/stub-sources.txt"
    CP="$BUILD_DIR/stubs"

    # Download Groovy
    GROOVY_JAR="$DEPS_DIR/groovy-${GROOVY_VERSION}.jar"
    if [ ! -f "$GROOVY_JAR" ]; then
        echo "Downloading Groovy ${GROOVY_VERSION}..."
        curl -fsSL "$GROOVY_URL" -o "$GROOVY_JAR"
    fi
    CP="$CP:$GROOVY_JAR"
fi

echo "Output:  $DIST_DIR/$JAR_NAME"
echo ""

find "$SRC_DIR" -name "*.java" -exec printf '"%s"\n' {} \; > "$BUILD_DIR/sources.txt"
echo "Compiling $(wc -l < "$BUILD_DIR/sources.txt" | tr -d ' ') Java file(s)..."

javac \
    --release 11 \
    -cp "$CP" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

echo "Compilation successful."

cp -r "$RES_DIR"/* "$BUILD_DIR/classes/"

(cd "$BUILD_DIR/classes" \
    && printf "Manifest-Version: 1.0\nImplementation-Title: groovy-loader-plugin\nImplementation-Version: %s\n" "$VERSION" > MANIFEST.MF \
    && jar cfm "$DIST_DIR/$JAR_NAME" MANIFEST.MF . \
    && rm MANIFEST.MF)

echo ""
echo "Built: $DIST_DIR/$JAR_NAME"
echo "Size:  $(du -h "$DIST_DIR/$JAR_NAME" | cut -f1)"

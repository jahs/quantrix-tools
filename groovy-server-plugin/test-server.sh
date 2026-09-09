#!/bin/bash
set -e

PLUGIN_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$PLUGIN_DIR/.." && pwd)"
GROOVY_VERSION="4.0.24"

if [ -n "${QX_APP:-}" ]; then
    QX_APP="$(cd "$QX_APP" && pwd)"
fi

cd "$PLUGIN_DIR"
./build.sh

CP="$PLUGIN_DIR/build/classes"
if [ -n "${QX_APP:-}" ]; then
    echo "Using Quantrix application JARs from $QX_APP"
    CP="$CP:$QX_APP/*:$QX_APP/lib/*"
else
    echo "Using API stubs and Groovy $GROOVY_VERSION from Maven Central"
    mkdir -p build/stubs build/deps
    find "$REPO_DIR/stubs" -name '*.java' -exec printf '"%s"\n' {} \; > build/stub-sources.txt
    javac --release 11 -d build/stubs @build/stub-sources.txt

    for module in groovy groovy-json; do
        curl -fsSL \
            "https://repo.maven.apache.org/maven2/org/apache/groovy/$module/$GROOVY_VERSION/$module-$GROOVY_VERSION.jar" \
            -o "build/deps/$module-$GROOVY_VERSION.jar"
    done
    CP="$CP:$PLUGIN_DIR/build/stubs:$PLUGIN_DIR/build/deps/*"
fi

java -Djava.awt.headless=true \
    -cp "$CP" \
    groovy.ui.GroovyMain tests/ServerPluginTest.groovy "$PLUGIN_DIR"

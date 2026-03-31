---
name: building-quantrix-plugins
description: Use when building plugins for Quantrix Modeler. Covers two approaches — Groovy plugins (recommended, no compilation) and Java plugins (for the loader or when Groovy isn't suitable). Includes examples, API guide, and the Groovy Loader Plugin source.
---

# Building Quantrix Plugins

For Quantrix concepts, formula syntax, and name quoting rules, read the [understanding-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/SKILL.md) skill.

There are two ways to build plugins for Quantrix Modeler 24.4:

## Approach 1: Groovy Plugins (Recommended)

Create a plugin directory (or JAR) in `~/Library/Application Support/Quantrix/groovy-plugins/` and it's loaded automatically by the Groovy Loader Plugin. No compilation needed. Hot-reload via Plugins → Manage Groovy Plugins → Reload.

**Start here:**
- Read `references/GROOVY_PLUGINS.md`
- Read `example/groovy/hello-plugin.groovy`
- Read `references/QUANTRIX_DESKTOP_API_GUIDE.md` for the full Quantrix API

**When to use:** Most plugins — HTTP servers, automation, model manipulation, UI extensions. Groovy has full access to all Quantrix APIs, JDK classes, and the scripting context (pipe syntax, `model`, `matrix` variables).

## Approach 2: Java Plugins

Write Java, compile to a JAR targeting Java 11, install in `~/Library/Application Support/Quantrix/plugins/`. Required for the Groovy Loader Plugin itself, and for plugins that need to integrate deeply with the Quantrix plugin framework (custom extension points).

**Start here:**
- Read `references/JAVA_PLUGINS.md`
- Read `example/src/main/java/com/example/quantrix/HelloPlugin.java`
- Read `references/QUANTRIX_DESKTOP_API_GUIDE.md` for the full Quantrix API

**When to use:** The Groovy Loader Plugin (already built — see `groovy-loader-plugin/` in the repo root). Custom extension points that require `plugin.xml` registration.

## Key Locations

| Path | Purpose |
|------|---------|
| `~/Library/Application Support/Quantrix/plugins/` | Java plugin JARs |
| `~/Library/Application Support/Quantrix/groovy-plugins/` | Groovy plugin directories and JARs |
| `~/Library/Application Support/Quantrix/logs/error.log` | Plugin log output |
| `~/Library/Application Support/Quantrix/user_config/` | User config and policy |

## Reference Docs

| Doc | Contents |
|-----|----------|
| `references/GROOVY_PLUGINS.md` | Groovy plugin development guide |
| `references/JAVA_PLUGINS.md` | Java plugin development guide |
| `references/QUANTRIX_DESKTOP_API_GUIDE.md` | Complete Quantrix Desktop Java/Groovy API |

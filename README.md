# Quantrix Tools

Plugins, scripts, and skills for working with [Quantrix Modeler](https://quantrix.com) — a genuinely excellent multi-dimensional spreadsheet. If you haven't tried it: go buy it. It's what spreadsheets should have been all along.

## What's in here

**groovy-loader-plugin/** — A Java plugin that loads Groovy plugins into Quantrix from a directory. Each plugin gets its own classloader for isolation and clean hot-reloading. Includes a manager UI (Plugins → Manage Groovy Plugins) showing status, version, and controls for each plugin.

**groovy-server-plugin/** — A Groovy plugin (loaded by the above) that runs a localhost HTTP server inside Quantrix. Gives you full programmatic control of open models: run scripts, read/write cells by named coordinates, manage formulas, inspect structure. Includes the pipe-syntax preprocessor, so scripts submitted via the API get the same `|Matrix::Item|` shorthand as the in-app console — but with correct handling of strings, comments, and GString interpolation.

**skills/** — Four skills for ~/.agents compatible coding agents, and Claude Code:

- **understanding-quantrix** — Core concepts, formula syntax, name quoting rules, cell reference grammar. The other skills build on this.
- **reading-quantrix-models** — Parse `.model` and `.modelt` files offline with a CLI and Python library.
- **managing-quantrix** — Control a live Quantrix session via the server plugin's REST API.
- **building-quantrix-plugins** — Write Groovy or Java plugins for Quantrix.

## Install

```bash
curl -sL https://raw.githubusercontent.com/jahs/quantrix-tools/main/install.sh | bash
```

This installs:

- **Groovy Loader and Server plugins** into Quantrix (required — everything else talks to these)
- **Skills** into `~/.agents/skills/` for ~/.agents-compatible agents (Codex, Gemini, OpenCode, Copilot, etc.)
- **Claude Code plugin** if the `claude` CLI is on `$PATH` — same skills plus an MCP server fronting `qxctl`. The MCP server runs on your machine, reads the auth token from disk, and connects to localhost, so sandboxed Claude environments (Cowork, remote agents) can drive Quantrix without direct network or filesystem access.

Restart Quantrix afterwards to pick up the JARs.

### uv (Claude plugin only)

The Claude plugin's MCP server launches via [`uv`](https://astral.sh/uv), which auto-fetches Python and the `mcp` package on first run:

```bash
# macOS / Linux
curl -LsSf https://astral.sh/uv/install.sh | sh

# Windows (PowerShell)
irm https://astral.sh/uv/install.ps1 | iex
# or:  winget install --id=astral-sh.uv
```

`install.sh` warns at the end if `uv` is missing.

### Manual Claude plugin install

If you'd rather not run `install.sh`, install the plugin from inside Claude Code:

```
/plugin marketplace add jahs/quantrix-tools
/plugin install quantrix-tools@quantrix-tools
```

You'll still need the Quantrix-side JARs (loader + server plugins) installed for the MCP server to talk to anything.

The plugin exposes seven MCP tools mirroring qxctl: `status`, `models`, `eval`, `eval_unsafe`, `plugins`, `reload_plugin`, `reload_all`. The skills drive how to use them — start with [managing-quantrix](skills/managing-quantrix/SKILL.md).

## Building

The preprocessor (pure Java, zero dependencies) builds and tests with:

```bash
cd groovy-server-plugin && ./build.sh
```

The loader plugin needs the Quantrix application JARs on the classpath:

```bash
cd groovy-loader-plugin && ./build.sh
```

## Versioning

Versions are derived from git tags (`v0.2.0`, `v0.3.0`, etc.). Build scripts use `git describe` — on a tagged commit you get `0.2.0`, after subsequent commits you get `0.2.0-3-gabcdef`. No VERSION files to maintain.

To release: `git tag v0.3.0 && git push --tags` — CI builds the JARs and creates a GitHub Release.

## License

See [LICENSE](LICENSE).

# Quantrix Tools

Plugins, scripts, and Claude Code skills for working with [Quantrix Modeler](https://quantrix.com) — a genuinely excellent multi-dimensional spreadsheet. If you haven't tried it: go buy it. It's what spreadsheets should have been all along.

## What's in here

**groovy-loader-plugin/** — A Java plugin that loads Groovy plugins into Quantrix from a directory. Each plugin gets its own classloader for isolation and clean hot-reloading. Includes a manager UI (Plugins → Manage Groovy Plugins) showing status, version, and controls for each plugin.

**groovy-server-plugin/** — A Groovy plugin (loaded by the above) that runs a localhost HTTP server inside Quantrix. Gives you full programmatic control of open models: run scripts, read/write cells by named coordinates, manage formulas, inspect structure. Includes the pipe-syntax preprocessor, so scripts submitted via the API get the same `|Matrix::Item|` shorthand as the in-app console — but with correct handling of strings, comments, and GString interpolation.

**skills/** — Four [Claude Code](https://docs.anthropic.com/en/docs/claude-code) skills that teach Claude how to work with Quantrix:

- **understanding-quantrix** — Core concepts, formula syntax, name quoting rules, cell reference grammar. The other skills build on this.
- **reading-quantrix-models** — Parse `.model` and `.modelt` files offline with a CLI and Python library.
- **managing-quantrix** — Control a live Quantrix session via the server plugin's REST API.
- **building-quantrix-plugins** — Write Groovy or Java plugins for Quantrix.

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

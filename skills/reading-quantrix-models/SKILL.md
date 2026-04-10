---
name: reading-quantrix-models
description: Read, analyze, and extract data from Quantrix Modeler files (.model, .modelt, and .xmodel) offline. Use this skill when the user wants to inspect model files directly from disk — including matrices, formulas, dimensions, cell data, charts, canvas layouts, scripts, or XML structure. Trigger on Quantrix model file extensions or explicit offline file-analysis tasks. For live automation against a running Quantrix app, use managing-quantrix instead.
---

# Reading Quantrix Model Files

This skill is for offline model-file inspection. For Quantrix concepts, syntax, and formula semantics, read the [understanding-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/SKILL.md) skill. For driving a running Quantrix desktop session, use [managing-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/managing-quantrix/SKILL.md).

Quantrix model files (`.model`, `.modelt`, and `.xmodel`) are (possibly Blowfish-encrypted) gzip-compressed XML.

## Setup

```bash
uv pip install --exclude-newer "30 days" numpy pandas pycryptodome
```

## CLI commands

`scripts/qx.py` efficiently handles decryption, parsing, and data extraction.

```bash
python3 scripts/qx.py info MODEL                    # overview: matrices, shapes, shared categories, scripts
python3 scripts/qx.py data MODEL "Matrix" \         # extract matrix data as table
  [--columns CAT ...] [--where CAT ITEM ...] [--head N] [--format tsv|csv|json]
python3 scripts/qx.py formulas MODEL [MATRIX]       # show formulas (all matrices if MATRIX omitted)
python3 scripts/qx.py categories MODEL [MATRIX] [--no-items]   # categories, items, linkage
python3 scripts/qx.py scripts MODEL [NAME]          # Groovy script source code
python3 scripts/qx.py views MODEL                   # model browser view tree
python3 scripts/qx.py xml MODEL [--xpath EXPR] [--with-data]   # raw decrypted XML (data stripped by default)
```

Key `data` options: `--where Region NY CT` filters to specific items (repeatable per category); `--format json` is token-efficient; `--head 0` for unlimited rows.

`categories` shows items by default; `--no-items` hides them for a compact overview. `xml` strips cell data by default (can be GBs); `--with-data` includes it.

For Python library usage (`QxModel.load`, `to_dataframe`), see `references/library-usage.md`.

## Formulas

Formulas are Quantrix-specific syntax stored per-matrix. Use `scripts/qx.py formulas` to read them.

For formula syntax, name quoting rules, and cell reference patterns, read the [understanding-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/SKILL.md) skill. The formal EBNF grammar is at [formula.ebnf](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/formula.ebnf).

HTML entities in XML-stored formulas: `&quot;` for `"`, `&amp;` for `&`.

Full function library and advanced patterns: [formulas.md](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/formulas.md).

## Detailed reference

- [formulas.md](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/formulas.md) — Generic Quantrix formula reference: functions, operators, positional syntax, and common patterns.
- [scripts.md](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/managing-quantrix/references/scripts.md) — QGroovy scripting reference: action vs function scripts, pipe syntax, model manipulation, user interaction, and export.
- `references/xml-schema.md` — Every XML element, attribute, and factory-id in the `.model` format. Use `scripts/qx.py xml` to dump raw XML for anything not covered by the CLI.
- `references/library-usage.md` — Python API: `QxModel.load`, `to_dataframe`, accessing structure/formulas/scripts programmatically.

Quantrix official docs:
- Formulas: https://help.idbs.com/Quantrix/Modeler_Help/LATEST/en/working-with-formulae.html
- Scripting: https://quantrix.com/help/scripting/
- Full help: https://help.idbs.com/Quantrix/Modeler_Help/LATEST

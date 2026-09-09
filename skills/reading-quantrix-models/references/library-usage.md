# qx.py Library Usage

For programmatic access to Quantrix models from Python, import `QxModel` directly.

## Loading models

```python
from scripts.qx import QxModel

model = QxModel.load("file.model")                          # full load (all data)
model = QxModel.load("file.model", keep_data_for="P&L")     # stream, keep one matrix's data
model = QxModel.load("file.model", keep_data_for=False)      # stream, structure only (fastest)
```

`keep_data_for` controls memory usage on large models:
- `None` (default) — loads everything into memory (backwards compatible)
- `False` — streams XML, discards all cell data (structure/formulas/scripts only)
- `"Matrix Name"` — streams XML, keeps cell data only for the named matrix

## Extracting data as a DataFrame

```python
matrix = model.matrix("P&L")
df = matrix.to_dataframe(
    where={"Scenario": ["Best"], "Region": ["NY", "CT"]},  # filter categories to items
    columns=["Year", "Quarter"],                    # categories on column axis
    head=20,                                        # limit output rows
)
```

- `where` filters reduce the data before building the DataFrame
- `columns` controls which categories appear as column headers vs row index
- When `keep_data_for` was used to strip data, calling `to_dataframe()` on a stripped matrix raises `RuntimeError` with a helpful message

## Grouped items

`category.items`, DataFrame labels, and CLI category listings use full group paths
in document order, such as `Assets.Tax` and `Liabilities.Tax`. Each leaf occupies
one category position; group containers add none.

```python
balance = model.matrix("Balance")
df = balance.to_dataframe(where={"Accounts": ["Liabilities.Tax"]})
```

Exact displayed paths take precedence, so a root-level `Tax` label selects that
root item. Otherwise, a bare leaf name such as `Cash` works only when unique in
the category. Ambiguous names raise `ValueError` with the matching paths rather
than selecting the first item. `category.item_index(name)` uses the same lookup
rules and returns the item's zero-based category index.

Paths single-quote components containing literal dots or apostrophes, doubling
embedded apostrophes. Thus `'Assets.Tax'` is one literal item name, while
`Assets.Tax` is `Tax` inside `Assets`. Other characters, including spaces and
leading digits, retain their spelling in these offline-reader labels. Use the
labels from `categories` verbatim in filters:

```bash
python3 scripts/qx.py data file.model Balance --where Accounts "'Assets.Tax'"
```

## Accessing model structure

```python
for name, m in model.matrices.items():
    print(name, m.shape)            # e.g. "P&L", (5, 15)

for cat_name, mat_names in model.shared_categories().items():
    print(cat_name, "->", mat_names)  # category_name -> [matrix_name, ...]

for lib, scripts in model.scripts():
    for script_name, source in scripts:
        print(f"{lib}/{script_name}")
        print(source)

for folder, view_type, matrix_name in model.views():
    print(f"{folder} [{view_type}] {matrix_name}")
```

## Formulas

```python
matrix = model.matrix("P&L")
for formula in matrix.formulas:
    print(formula)                  # raw formula text as stored in XML
```

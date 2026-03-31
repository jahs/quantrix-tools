#!/usr/bin/env python3
"""
qx.py — Quantrix model reader (library + CLI)

Read, analyze, and extract data from Quantrix Modeler files (.model / .modelt).

Dependencies: pycryptodome, pandas
    pip install pycryptodome pandas

Usage (CLI):
    python3 qx.py info       MODEL
    python3 qx.py data       MODEL MATRIX [--columns CAT ...] [--where CAT ITEM ...] [--head N] [--format tsv|csv|json]
    python3 qx.py formulas   MODEL [MATRIX]
    python3 qx.py categories MODEL [MATRIX] [--items]
    python3 qx.py scripts    MODEL [NAME]
    python3 qx.py views      MODEL
    python3 qx.py xml        MODEL [--xpath EXPR]

Usage (library):
    from scripts.qx import QxModel
    model = QxModel.load("file.model")
    df = model.matrix("Profit & Loss").to_dataframe()
"""

import argparse
import gzip
import html
import io
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from functools import reduce
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union

# numpy and pandas are imported lazily in to_dataframe() to keep
# the tool lightweight for commands that don't need them.


BLOWFISH_KEY_HEX = "037f37690b21377b"  # standard Quantrix model-file key (not a secret)


def _decrypt_pycryptodome(data: bytes) -> bytes:
    from Crypto.Cipher import Blowfish
    from Crypto.Util.Padding import unpad

    cipher = Blowfish.new(bytes.fromhex(BLOWFISH_KEY_HEX), Blowfish.MODE_ECB)
    return unpad(cipher.decrypt(data), Blowfish.block_size)


def _decrypt_openssl(data: bytes) -> bytes:
    result = subprocess.run(
        [
            "openssl", "enc", "-d", "-bf-ecb",
            "-K", BLOWFISH_KEY_HEX + BLOWFISH_KEY_HEX,
        ],
        input=data,
        capture_output=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"openssl failed: {result.stderr.decode()}")
    return result.stdout


def decrypt_model(path: Union[str, Path]) -> ET.Element:
    """Read a .model/.modelt/.xmodel file and return the parsed XML root."""
    raw = Path(path).read_bytes()

    if raw[:2] == b"\x1f\x8b":
        # Plain gzip
        xml_bytes = gzip.decompress(raw)
    elif raw[:2] == b"\x00\x00":
        # Blowfish-encrypted gzip
        encrypted = raw[2:]
        try:
            decrypted = _decrypt_pycryptodome(encrypted)
        except Exception:
            decrypted = _decrypt_openssl(encrypted)
        xml_bytes = gzip.decompress(decrypted)
    else:
        # Assume plain XML (e.g. .xmodel)
        xml_bytes = raw

    return ET.fromstring(xml_bytes)


def _open_model_stream(path: Union[str, Path]):
    """Open a model file and return a readable stream of decompressed XML."""
    path = Path(path)
    with path.open("rb") as f:
        header = f.read(2)
    if header == b"\x1f\x8b":
        return gzip.open(path, "rb")
    elif header == b"\x00\x00":
        raw = path.read_bytes()
        try:
            decrypted = _decrypt_pycryptodome(raw[2:])
        except Exception:
            decrypted = _decrypt_openssl(raw[2:])
        return gzip.GzipFile(fileobj=io.BytesIO(decrypted))
    else:
        return path.open("rb")


def _iterparse_model(
    path: Union[str, Path],
    keep_data_for: Optional[str] = None,
) -> ET.Element:
    """Stream-parse a model, clearing <data> text to save memory.

    Uses iterparse so that cell data (which can be GBs) is discarded as
    the parser encounters each <table> — only the matrix named by
    keep_data_for retains its data text.

    Args:
        keep_data_for: Matrix name whose data to keep. None strips all.
    """
    stream = _open_model_stream(path)
    tag_stack: list[str] = []
    current_table_name: Optional[str] = None
    root = None

    for event, elem in ET.iterparse(stream, events=("start", "end")):
        if event == "start":
            tag_stack.append(elem.tag)
            if root is None:
                root = elem
        else:
            # Direct child of <table>: <name> always precedes <data>
            if len(tag_stack) >= 2 and tag_stack[-2] == "table":
                if elem.tag == "name" and current_table_name is None:
                    current_table_name = elem.text
                elif elem.tag == "data":
                    if keep_data_for is None or current_table_name != keep_data_for:
                        elem.text = None
            if elem.tag == "table":
                current_table_name = None
            tag_stack.pop()

    if hasattr(stream, "close"):
        stream.close()
    return root


# ---------------------------------------------------------------------------
# Data parsing
# ---------------------------------------------------------------------------

_ESCAPE_SENTINEL = '\x00'  # stands in for \, during split (XML forbids \x00)


def parse_data_string(text: str) -> list:
    """Parse Quantrix comma-separated data string into Python values.

    Uses C-level str.replace + str.split instead of a Python char loop.
    """
    if not text:
        return []
    clean = text.replace('\\,', _ESCAPE_SENTINEL)
    parts = clean.split(',')
    return [_parse_cell(s.replace(_ESCAPE_SENTINEL, ',') if _ESCAPE_SENTINEL in s else s)
            for s in parts]


def _parse_cell(s: str):
    """Convert a raw cell string to float, str, or None.

    Fast-paths the common numeric case through C-level float().
    """
    if not s:
        return None
    # Fast path: pure number (no |N suffix, no string prefix)
    if '|' not in s and s[0] != "'":
        try:
            return float(s)
        except ValueError:
            return s
    # Handle |N suffix (e.g. "250000.0|6")
    pipe = s.rfind("|")
    if pipe > 0 and s[pipe + 1:].isdigit():
        s = s[:pipe]
    if not s:
        return None
    if s[0] == "'":
        return s[1:]
    try:
        return float(s)
    except ValueError:
        return s


def _parse_data_selective(text: str, needed: set) -> dict:
    """Parse only values at specified flat indices from a data string.

    Uses C-level str.find to skip to needed positions without materializing
    every value. Short-circuits once past max(needed).
    """
    if not text or not needed:
        return {}

    sorted_needed = sorted(needed)
    max_needed = sorted_needed[-1]
    result = {}
    pos = 0
    idx = 0
    n = len(text)

    for target in sorted_needed:
        # Skip forward to target index using C-level find
        while idx < target:
            nc = text.find(',', pos)
            if nc == -1:
                return result
            # Skip escaped commas (\,)
            if nc > 0 and text[nc - 1] == '\\':
                pos = nc + 1
                continue
            pos = nc + 1
            idx += 1

        # Extract value at current position
        end = pos
        while True:
            nc = text.find(',', end)
            if nc == -1:
                end = n
                break
            if nc > 0 and text[nc - 1] == '\\':
                end = nc + 1
                continue
            end = nc
            break

        s = text[pos:end]
        if '\\,' in s:
            s = s.replace('\\,', ',')
        result[target] = _parse_cell(s)

        pos = end + 1 if end < n else n
        idx += 1

    return result


# ---------------------------------------------------------------------------
# Category / Matrix model
# ---------------------------------------------------------------------------

@dataclass
class QxCategory:
    name: str
    oid: str
    items: List[str]  # leaf item names in document order
    groups: Optional[ET.Element] = field(default=None, repr=False)

    @property
    def size(self) -> int:
        return len(self.items)


@dataclass
class QxFormula:
    text: str
    oid: str


@dataclass
class QxMatrix:
    name: str
    oid: str
    categories: List[QxCategory]  # in data order (file order)
    formulas: List[QxFormula]
    hidden: bool
    _data_element: Optional[ET.Element] = field(default=None, repr=False)
    _data_stripped: bool = field(default=False, repr=False)
    _default_columns: Optional[List[str]] = field(default=None, repr=False)

    @property
    def shape(self) -> Tuple[int, ...]:
        return tuple(c.size for c in self.categories)

    @property
    def size(self) -> int:
        return reduce(lambda a, b: a * b, self.shape, 1)

    @property
    def category_names(self) -> List[str]:
        return [c.name for c in self.categories]

    @property
    def default_column_categories(self) -> List[str]:
        """Column categories from the spreadsheetView, if known."""
        return self._default_columns or []

    def get_data_flat(self) -> list:
        """Parse and return the flat data array (column-major)."""
        if self._data_element is None or not self._data_element.text:
            if self._data_stripped:
                raise RuntimeError(
                    f"Data for '{self.name}' was not loaded. "
                    f"Use: QxModel.load(path, keep_data_for='{self.name}')"
                )
            return [None] * self.size
        return parse_data_string(self._data_element.text)

    def to_dataframe(
        self,
        columns: Optional[List[str]] = None,
        where: Optional[Dict[str, List[str]]] = None,
        head: Optional[int] = None,
    ) -> "pd.DataFrame":
        """Build a pandas DataFrame from this matrix.

        Args:
            columns: Category names to place on the column axis.
                     Defaults to the spreadsheetView's column-axis.
            where: Dict of {category_name: [item_names]} to filter/slice.
            head: Limit to first N rows of output.
        """
        import numpy as np
        import pandas as pd

        if columns is None:
            columns = self.default_column_categories

        # Validate column category names
        cat_names = self.category_names
        for c in columns:
            if c not in cat_names:
                raise ValueError(
                    f"Category '{c}' not in matrix. "
                    f"Available: {cat_names}"
                )

        # Validate where keys
        if where:
            for c in where:
                if c not in cat_names:
                    raise ValueError(
                        f"Where category '{c}' not in matrix. "
                        f"Available: {cat_names}"
                    )

        shape = self.shape
        n_cats = len(self.categories)

        # Compute strides (column-major: first category varies fastest)
        strides = [1]
        for i in range(n_cats - 1):
            strides.append(strides[-1] * shape[i])

        # Build index arrays for each category
        cat_items = [c.items for c in self.categories]

        # Apply where filter: determine which indices to keep per category
        keep_indices = []
        for i, cat in enumerate(self.categories):
            if where and cat.name in where:
                item_names = where[cat.name]
                indices = []
                for item_name in item_names:
                    if item_name not in cat.items:
                        raise ValueError(
                            f"Item '{item_name}' not in category "
                            f"'{cat.name}'. "
                            f"Available: {cat.items[:20]}"
                        )
                    indices.append(cat.items.index(item_name))
                keep_indices.append(indices)
            else:
                keep_indices.append(list(range(cat.size)))

        # Separate row and column categories
        col_cat_indices = [cat_names.index(c) for c in columns]
        row_cat_indices = [
            i for i in range(n_cats)
            if i not in col_cat_indices
            and not (where and self.categories[i].name in where
                     and len(keep_indices[i]) == 1)
        ]

        # If where filters a category to a single item AND it's not in
        # columns, it becomes a fixed scalar — don't include in row index.
        # But if it IS in columns, keep it (single-item column).
        # Recalculate: categories that appear in neither row nor col
        # because they're fixed by where — these are "fixed" dims
        fixed_indices = [
            i for i in range(n_cats)
            if i not in col_cat_indices and i not in row_cat_indices
        ]

        # Build the cartesian products for rows and columns
        row_combos = _cartesian_product([keep_indices[i] for i in row_cat_indices])
        col_combos = _cartesian_product([keep_indices[i] for i in col_cat_indices])

        if not col_combos:
            # No column categories — single value column
            col_combos = [()]
        if not row_combos:
            # No row categories — single row
            row_combos = [()]

        # Build column headers
        if col_cat_indices:
            if len(col_cat_indices) == 1:
                col_headers = [
                    cat_items[col_cat_indices[0]][idx[0]]
                    for idx in col_combos
                ]
            else:
                col_headers = pd.MultiIndex.from_tuples(
                    [
                        tuple(cat_items[col_cat_indices[j]][idx[j]]
                              for j in range(len(col_cat_indices)))
                        for idx in col_combos
                    ],
                    names=[self.categories[i].name for i in col_cat_indices],
                )
        else:
            col_headers = ["Value"]

        # Build row index
        if row_cat_indices:
            if len(row_cat_indices) == 1:
                ci = row_cat_indices[0]
                row_index = pd.Index(
                    [cat_items[ci][idx[0]] for idx in row_combos],
                    name=self.categories[ci].name,
                )
            else:
                row_index = pd.MultiIndex.from_tuples(
                    [
                        tuple(cat_items[row_cat_indices[j]][idx[j]]
                              for j in range(len(row_cat_indices)))
                        for idx in row_combos
                    ],
                    names=[self.categories[i].name for i in row_cat_indices],
                )
        else:
            row_index = pd.RangeIndex(1)

        # Apply head limit to rows
        if head is not None:
            row_combos = row_combos[:head]
            if isinstance(row_index, pd.MultiIndex):
                row_index = row_index[:head]
            else:
                row_index = row_index[:head]

        # Fixed dimension indices (from where filter)
        fixed_idx_values = {i: keep_indices[i][0] for i in fixed_indices}

        # Vectorized flat index computation via numpy
        # flat_idx = sum(index_for_cat[k] * stride[k]) for each output cell
        fixed_contrib = sum(
            idx * strides[ci] for ci, idx in fixed_idx_values.items())

        if row_cat_indices:
            row_arr = np.array(row_combos, dtype=np.intp)
            row_strides_arr = np.array(
                [strides[ci] for ci in row_cat_indices], dtype=np.intp)
            row_contrib = row_arr @ row_strides_arr
        else:
            row_contrib = np.zeros(len(row_combos), dtype=np.intp)

        if col_cat_indices:
            col_arr = np.array(col_combos, dtype=np.intp)
            col_strides_arr = np.array(
                [strides[ci] for ci in col_cat_indices], dtype=np.intp)
            col_contrib = col_arr @ col_strides_arr
        else:
            col_contrib = np.zeros(len(col_combos), dtype=np.intp)

        flat_grid = (row_contrib[:, np.newaxis]
                     + col_contrib[np.newaxis, :]
                     + fixed_contrib)

        # Fetch values — selective parse when reading a subset of the data
        needed = set(flat_grid.ravel().tolist())
        total = self.size

        if len(needed) < total:
            if self._data_element is None or not self._data_element.text:
                if self._data_stripped:
                    raise RuntimeError(
                        f"Data for '{self.name}' was not loaded. "
                        f"Use: QxModel.load(path, keep_data_for='{self.name}')"
                    )
                values = {}
            else:
                values = _parse_data_selective(
                    self._data_element.text, needed)
            # Selective output is typically small — Python lookup is fine
            nrows, ncols = flat_grid.shape
            data = [[values.get(int(flat_grid[r, c]))
                     for c in range(ncols)] for r in range(nrows)]
        else:
            # Full data: numpy fancy indexing for the lookup
            flat = self.get_data_flat()
            flat_arr = np.array(flat, dtype=object)
            if flat_grid.max() < len(flat_arr):
                data = flat_arr[flat_grid]
            else:
                safe = np.minimum(flat_grid, len(flat_arr) - 1)
                data = flat_arr[safe]
                data[flat_grid >= len(flat_arr)] = None

        return pd.DataFrame(data, index=row_index, columns=col_headers)


def _cartesian_product(lists: List[List[int]]) -> List[tuple]:
    """Cartesian product of a list of index lists."""
    if not lists:
        return []
    result = [()]
    for lst in lists:
        result = [t + (v,) for t in result for v in lst]
    return result


# ---------------------------------------------------------------------------
# QxModel — top level
# ---------------------------------------------------------------------------

def _get_leaf_items(children_el: Optional[ET.Element]) -> List[str]:
    """Recursively collect leaf item names in document order."""
    if children_el is None:
        return []
    leaves = []
    for child in children_el:
        fid = child.get("factory-id", "")
        if fid == "group":
            leaves.extend(_get_leaf_items(child.find("children")))
        else:
            name_el = child.find("name")
            if name_el is not None and name_el.text:
                leaves.append(name_el.text)
    return leaves


class QxModel:
    """Parsed Quantrix model."""

    def __init__(self, root: ET.Element, source_path: Optional[str] = None):
        self.root = root
        self.source_path = source_path
        self._categories: Dict[str, QxCategory] = {}  # oid -> QxCategory
        self._matrices: Dict[str, QxMatrix] = {}  # name -> QxMatrix
        self._matrices_by_oid: Dict[str, QxMatrix] = {}
        self._cat_matrices: Dict[str, List[Tuple[str, bool]]] = {}  # oid -> [(matrix_name, is_linked)]
        self._parse()

    @classmethod
    def load(cls, path: Union[str, Path], keep_data_for=None) -> "QxModel":
        """Load a model file.

        Args:
            keep_data_for: Controls cell data loading for memory efficiency.
                None (default) — load all data (backwards compatible).
                False — stream, strip all cell data (structure only).
                "Matrix Name" — stream, keep data for this matrix only.
        """
        if keep_data_for is None:
            root = decrypt_model(path)
        else:
            matrix_name = keep_data_for if keep_data_for else None
            root = _iterparse_model(path, keep_data_for=matrix_name)
        return cls(root, source_path=str(path))

    def _parse(self):
        root = self.root

        # Pass 1: collect all category definitions (oid -> element)
        cat_elements: Dict[str, ET.Element] = {}
        for cat_el in root.iter("category"):
            oid = cat_el.get("oid")
            if oid:
                cat_elements[oid] = cat_el

        # Pass 2: build QxCategory objects
        for oid, el in cat_elements.items():
            name_el = el.find("name")
            name = name_el.text if name_el is not None else f"_cat_{oid}"
            items = _get_leaf_items(el.find("children"))
            self._categories[oid] = QxCategory(
                name=name, oid=oid, items=items, groups=el.find("children")
            )

        # Pass 3: build QxMatrix objects from <table> elements
        for table in root.findall(".//tables/table"):
            name_el = table.find("name")
            if name_el is None:
                continue
            mat_name = name_el.text
            mat_oid = table.get("oid", "")
            hidden = table.find("hidden")
            is_hidden = hidden is not None and hidden.text == "true"

            # Resolve categories in file order, tracking ownership
            cats_el = table.find("categories")
            mat_cats = []
            if cats_el is not None:
                for cat_el in cats_el:
                    ref = cat_el.get("ref")
                    oid = cat_el.get("oid")
                    if ref and ref in self._categories:
                        mat_cats.append(self._categories[ref])
                        self._cat_matrices.setdefault(ref, []).append(
                            (mat_name, True))  # linked
                    elif oid and oid in self._categories:
                        mat_cats.append(self._categories[oid])
                        self._cat_matrices.setdefault(oid, []).append(
                            (mat_name, False))  # defines

            # Formulas
            formulas = []
            for f_el in table.findall(".//formula"):
                fs = f_el.find("formula-string")
                if fs is not None and fs.text:
                    formulas.append(QxFormula(
                        text=html.unescape(fs.text),
                        oid=f_el.get("oid", ""),
                    ))

            data_el = table.find("data")
            data_stripped = (
                data_el is not None
                and data_el.text is None
                and data_el.get("size", "0") != "0"
            )

            matrix = QxMatrix(
                name=mat_name,
                oid=mat_oid,
                categories=mat_cats,
                formulas=formulas,
                hidden=is_hidden,
                _data_element=data_el,
                _data_stripped=data_stripped,
            )
            self._matrices[mat_name] = matrix
            self._matrices_by_oid[mat_oid] = matrix

        # Pass 4: resolve default column categories from spreadsheetViews
        self._resolve_view_axes()

    def _resolve_view_axes(self):
        """Find each matrix's main spreadsheetView and extract column-axis categories."""
        for view_el in self.root.iter("view"):
            if view_el.get("factory-id") != "spreadsheetView":
                continue

            # Find the inner view with axes (spreadsheetView has nested <view>)
            inner = view_el.find("view")
            if inner is None:
                inner = view_el

            # Get table ref from projection state
            table_ref = None
            for state in inner.iter("state"):
                tr = state.find("table")
                if tr is not None:
                    table_ref = tr.get("ref")
                    break
            if not table_ref:
                continue

            matrix = self._matrices_by_oid.get(table_ref)
            if not matrix or matrix._default_columns is not None:
                # Already assigned (first spreadsheetView wins = main view)
                continue

            # Extract column-axis category names
            col_axis = inner.find("column-axis")
            if col_axis is None:
                continue
            cats_el = col_axis.find("categories")
            if cats_el is None:
                continue

            col_names = []
            for cat_ref in cats_el:
                ref = cat_ref.get("ref")
                if ref and ref in self._categories:
                    col_names.append(self._categories[ref].name)
            matrix._default_columns = col_names

    @property
    def matrices(self) -> Dict[str, QxMatrix]:
        return self._matrices

    def matrix(self, name: str) -> QxMatrix:
        if name not in self._matrices:
            available = list(self._matrices.keys())
            raise KeyError(
                f"Matrix '{name}' not found. Available: {available}"
            )
        return self._matrices[name]

    @property
    def categories(self) -> Dict[str, QxCategory]:
        return self._categories

    def shared_categories(self) -> Dict[str, List[str]]:
        """Return {category_name: [matrix_names...]} for truly linked categories.

        Only includes categories where multiple matrices share the same OID
        (via ref=), not categories that merely have the same name.
        """
        result: Dict[str, List[str]] = {}
        for oid, usage in self._cat_matrices.items():
            if len(usage) > 1:
                cat = self._categories[oid]
                result[cat.name] = [name for name, _linked in usage]
        return result

    def scripts(self) -> List[Tuple[str, List[Tuple[str, str]]]]:
        """Return [(library_name, [(script_name, source_code)...])]."""
        results = []
        for prop_store in self.root.iter("property-store"):
            for item in prop_store.findall(
                ".//item[@factory-id='com.quantrix.scripting.core.ScriptLibrary']"
            ):
                lib_name_el = item.find("name")
                lib_name = lib_name_el.text if lib_name_el is not None else "?"
                scripts_list = []
                for script_val in item.findall(".//scripts/value"):
                    sn = script_val.find("name")
                    name = sn.text if sn is not None and sn.text else "?"
                    src_el = script_val.find("script")
                    source = src_el.text if src_el is not None and src_el.text else ""
                    scripts_list.append((name, source))
                results.append((lib_name, scripts_list))
        return results

    def views(self) -> List[Tuple[str, str, Optional[str]]]:
        """Return [(folder_path, view_type, matrix_name)] from the model browser."""
        results = []
        browser = self.root.find("model-browser")
        if browser is None:
            return results
        br = browser.find("browser-root")
        if br is None:
            return results
        self._walk_browser(br, "", results)
        return results

    def _walk_browser(self, el, path, results):
        children = el.find("children")
        if children is None:
            return
        for child in children:
            fid = child.get("factory-id", "")
            if fid == "BrowserFolder":
                name_el = child.find("name")
                folder_name = name_el.text if name_el is not None else "?"
                self._walk_browser(child, path + folder_name + "/", results)
            elif fid == "BrowserViewNode":
                view_el = child.find("view")
                if view_el is not None:
                    view_type = view_el.get("factory-id", "unknown")
                    matrix_name = None
                    for state in view_el.iter("state"):
                        t = state.find("table")
                        if t is not None:
                            ref = t.get("ref", "")
                            if ref in self._matrices_by_oid:
                                matrix_name = self._matrices_by_oid[ref].name
                            break
                    results.append((path, view_type, matrix_name))


# ---------------------------------------------------------------------------
# CLI — info
# ---------------------------------------------------------------------------

LARGE_MATRIX_THRESHOLD = 50_000


def cmd_info(model: QxModel):
    """Print model overview: matrices, shared categories, scripts."""
    matrices = list(model.matrices.values())

    print(f"Matrices: {len(matrices)}")
    print()

    # Table of matrices
    for m in sorted(matrices, key=lambda x: x.size, reverse=True):
        shape_str = " x ".join(
            f"{c.name}({c.size})" for c in m.categories
        )
        col_str = ""
        if m.default_column_categories:
            col_str = f"  cols={':'.join(m.default_column_categories)}"
        hidden_str = "  (hidden)" if m.hidden else ""
        print(f"  {m.name}  [{shape_str}]  "
              f"size={m.size:,}  formulas={len(m.formulas)}{col_str}{hidden_str}")

    # Shared categories
    shared = model.shared_categories()
    if shared:
        print(f"\nShared categories:")
        for cat_name, mat_names in sorted(shared.items()):
            print(f"  {cat_name}: {', '.join(mat_names)}")

    # Scripts
    scripts = model.scripts()
    script_count = sum(len(s) for _, s in scripts)
    if script_count:
        print(f"\nScripts: {script_count}")
        for lib, scripts_list in scripts:
            for name, _source in scripts_list:
                print(f"  {lib}/{name}")

    # Total cells
    total = sum(m.size for m in matrices)
    print(f"\nTotal cells: {total:,}")


# ---------------------------------------------------------------------------
# CLI — data
# ---------------------------------------------------------------------------

def cmd_data(
    model: QxModel,
    matrix_name: str,
    columns: Optional[List[str]],
    where: Optional[Dict[str, List[str]]],
    head: Optional[int],
    fmt: str,
):
    """Print matrix data as a table."""
    m = model.matrix(matrix_name)

    # Safety check for large matrices
    if m.size > LARGE_MATRIX_THRESHOLD and head is None and not where:
        print(
            f"WARNING: '{m.name}' has {m.size:,} cells. "
            f"Limiting to {LARGE_MATRIX_THRESHOLD // 100} rows. "
            f"Use --head N to set a different limit, or --head 0 for all.",
            file=sys.stderr,
        )
        head = LARGE_MATRIX_THRESHOLD // 100

    if head == 0:
        head = None  # --head 0 means unlimited

    df = m.to_dataframe(columns=columns, where=where, head=head)

    if fmt == "tsv":
        print(df.to_csv(sep="\t", lineterminator="\n"), end="")
    elif fmt == "csv":
        print(df.to_csv(lineterminator="\n"), end="")
    elif fmt == "json":
        # Column-oriented JSON — more token-efficient
        out = {
            "matrix": m.name,
            "shape": {c.name: c.size for c in m.categories},
        }
        if where:
            out["where"] = where
        out["data"] = json.loads(df.to_json(orient="columns"))
        print(json.dumps(out, indent=2))
    else:
        raise ValueError(f"Unknown format: {fmt}")


# ---------------------------------------------------------------------------
# CLI — formulas
# ---------------------------------------------------------------------------

def cmd_formulas(model: QxModel, matrix_name: Optional[str]):
    """Print formulas for one or all matrices."""
    if matrix_name:
        matrices = [model.matrix(matrix_name)]
    else:
        matrices = [
            m for m in model.matrices.values()
            if m.formulas
        ]

    for m in matrices:
        if not m.formulas:
            continue
        if not matrix_name:
            print(f"=== {m.name} ===")
        for f in m.formulas:
            print(f"  {f.text}")
        print()


# ---------------------------------------------------------------------------
# CLI — categories
# ---------------------------------------------------------------------------

def cmd_categories(model: QxModel, matrix_name: Optional[str], show_items: bool):
    """Print categories with items, ownership, and linkage."""
    if matrix_name:
        m = model.matrix(matrix_name)
        for cat in m.categories:
            usage = model._cat_matrices.get(cat.oid, [])
            is_linked = any(
                mat_name == matrix_name and linked
                for mat_name, linked in usage
            )
            tag = " (linked)" if is_linked else ""
            print(f"  {cat.name}  [{cat.size} items]{tag}")
            if show_items:
                _print_items(cat.items)
        return

    # All categories — detect duplicate names
    cats = list(model.categories.values())
    name_to_oids: Dict[str, List[str]] = {}
    for cat in cats:
        name_to_oids.setdefault(cat.name, []).append(cat.oid)

    print(f"Categories: {len(cats)}")
    print()

    for cat in sorted(cats, key=lambda c: c.name):
        usage = model._cat_matrices.get(cat.oid, [])
        defines = [name for name, linked in usage if not linked]
        linked = [name for name, linked in usage if linked]
        is_shared = len(linked) > 0
        has_dup_name = len(name_to_oids.get(cat.name, [])) > 1

        # Header line
        header = f"  {cat.name}"
        if has_dup_name:
            header += f"  (oid {cat.oid})"
        header += f"  [{cat.size} items]"
        if is_shared:
            header += "  SHARED"
        print(header)

        if show_items:
            _print_items(cat.items)

        # Show matrix usage
        if defines:
            print(f"    Matrices: {', '.join(defines)}")
        if linked:
            print(f"    Linked by: {', '.join(linked)}")
        print()


def _print_items(items: List[str], max_show: int = 15):
    """Print item list, truncating if long."""
    if not items:
        print("    Items: (none)")
        return
    shown = items[:max_show]
    suffix = f" ... +{len(items) - max_show} more" if len(items) > max_show else ""
    print(f"    Items: {', '.join(shown)}{suffix}")


# ---------------------------------------------------------------------------
# CLI — scripts
# ---------------------------------------------------------------------------

def cmd_scripts(model: QxModel, script_name: Optional[str]):
    """Print script source code."""
    scripts = model.scripts()
    if not scripts or not any(s for _, s in scripts):
        print("No scripts in this model.")
        return

    for lib, scripts_list in scripts:
        for name, source in scripts_list:
            full_name = f"{lib}/{name}"
            if script_name and script_name not in (name, full_name, lib):
                continue
            print(f"=== {full_name} ===")
            if source:
                print(source)
            else:
                print("  (no source)")
            print()


# ---------------------------------------------------------------------------
# CLI — views
# ---------------------------------------------------------------------------

VIEW_TYPE_LABELS = {
    "spreadsheetView": "spreadsheet",
    "matrixView": "matrix",
    "presentationView": "canvas",
}


def cmd_views(model: QxModel):
    """Print the model browser view tree."""
    views = model.views()
    if not views:
        print("No views found.")
        return

    current_folder = None
    for folder, view_type, matrix_name in views:
        if folder != current_folder:
            current_folder = folder
            print(f"  {folder or '(root)/'}")
        label = VIEW_TYPE_LABELS.get(view_type, view_type)
        name = matrix_name or "(untitled)"
        print(f"    {name}  [{label}]")
    print()


# ---------------------------------------------------------------------------
# CLI — xml
# ---------------------------------------------------------------------------

def cmd_xml(path: str, xpath: Optional[str], with_data: bool = False):
    """Dump decrypted XML, optionally filtered by XPath.

    By default, <data> element text is stripped (can be GBs) and replaced
    with a comment showing the cell count. Use --with-data to include it.
    """
    if with_data:
        root = decrypt_model(path)
    else:
        root = _iterparse_model(path)
        for data_el in root.iter("data"):
            if data_el.text is None:
                size = data_el.get("size", "?")
                data_el.append(ET.Comment(
                    f" {size} cells — use: qx.py data MODEL MATRIX "))
    if xpath:
        elements = root.findall(xpath)
        if not elements:
            print(f"No elements matched: {xpath}", file=sys.stderr)
            sys.exit(1)
        for el in elements:
            ET.indent(el)
            print(ET.tostring(el, encoding="unicode"))
    else:
        ET.indent(root)
        print(ET.tostring(root, encoding="unicode"))


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def parse_where(
    groups: Optional[List[List[str]]],
) -> Optional[Dict[str, List[str]]]:
    """Parse [['Cat','Item1','Item2'], ...] into {cat: [items]} dict.

    Each group comes from one --where flag: first element is the category
    name, remaining elements are items.  Repeated categories accumulate:
      --where Region NY --where Region CT  ->  {"Region": ["NY", "CT"]}
    """
    if not groups:
        return None
    result: Dict[str, List[str]] = {}
    for group in groups:
        if len(group) < 2:
            raise ValueError(
                f"--where requires a category followed by at least one item, "
                f"got: {group}"
            )
        cat = group[0]
        result.setdefault(cat, []).extend(group[1:])
    return result


def main():
    parser = argparse.ArgumentParser(
        prog="qx",
        description="Read and analyze Quantrix model files",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # --- info ---
    p_info = sub.add_parser("info", help="Model overview")
    p_info.add_argument("model", help="Path to .model/.modelt/.xmodel file")

    # --- data ---
    p_data = sub.add_parser("data", help="Read matrix data")
    p_data.add_argument("model", help="Path to model file")
    p_data.add_argument("matrix", help="Matrix name")
    p_data.add_argument(
        "--columns", nargs="+", default=None, metavar="CAT",
        help="Categories for column axis (default: from view)",
    )
    p_data.add_argument(
        "--where", nargs="+", action="append", default=None,
        metavar="ITEM",
        help="Filter: --where CAT ITEM [ITEM ...] (repeatable)",
    )
    p_data.add_argument(
        "--head", type=int, default=None, metavar="N",
        help="Limit output rows (0 = unlimited)",
    )
    p_data.add_argument(
        "--format", choices=["tsv", "csv", "json"], default="tsv",
        dest="fmt", help="Output format (default: tsv)",
    )

    # --- formulas ---
    p_form = sub.add_parser("formulas", help="Show formulas")
    p_form.add_argument("model", help="Path to model file")
    p_form.add_argument("matrix", nargs="?", default=None, help="Matrix name (omit for all)")

    # --- categories ---
    p_cats = sub.add_parser("categories", help="Show categories with items and linkage")
    p_cats.add_argument("model", help="Path to model file")
    p_cats.add_argument("matrix", nargs="?", default=None, help="Matrix name (omit for all)")
    p_cats.add_argument(
        "--items", action="store_true", default=True,
        help="Show item names (default: on)",
    )
    p_cats.add_argument(
        "--no-items", action="store_false", dest="items",
        help="Hide item names",
    )

    # --- scripts ---
    p_scripts = sub.add_parser("scripts", help="Show script source code")
    p_scripts.add_argument("model", help="Path to model file")
    p_scripts.add_argument("name", nargs="?", default=None, help="Script or library name (omit for all)")

    # --- views ---
    p_views = sub.add_parser("views", help="Show model browser view tree")
    p_views.add_argument("model", help="Path to model file")

    # --- xml ---
    p_xml = sub.add_parser("xml", help="Dump decrypted XML (data stripped by default)")
    p_xml.add_argument("model", help="Path to model file")
    p_xml.add_argument(
        "--xpath", default=None, metavar="EXPR",
        help="XPath filter (e.g. './/formula-list')",
    )
    p_xml.add_argument(
        "--with-data", action="store_true", default=False,
        help="Include raw cell data (can be very large)",
    )

    args = parser.parse_args()

    # xml command works directly on the file, no QxModel needed
    if args.command == "xml":
        cmd_xml(args.model, args.xpath, args.with_data)
        return

    # data command streams and keeps only the target matrix's data;
    # all other commands stream with no data (structure only)
    if args.command == "data":
        model = QxModel.load(args.model, keep_data_for=args.matrix)
    else:
        model = QxModel.load(args.model, keep_data_for=False)

    if args.command == "info":
        cmd_info(model)
    elif args.command == "data":
        where = parse_where(args.where)
        cmd_data(model, args.matrix, args.columns, where, args.head, args.fmt)
    elif args.command == "formulas":
        cmd_formulas(model, args.matrix)
    elif args.command == "categories":
        cmd_categories(model, args.matrix, args.items)
    elif args.command == "scripts":
        cmd_scripts(model, args.name)
    elif args.command == "views":
        cmd_views(model)


if __name__ == "__main__":
    main()

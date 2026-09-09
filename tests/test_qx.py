import contextlib
import csv
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "skills/reading-quantrix-models/scripts/qx.py"
sys.path.insert(0, str(SCRIPT.parent))
from qx import QxModel, cmd_categories, cmd_data


ACCOUNTS = [
    ("Assets", ["Tax", "Cash"]),
    ("Liabilities", ["Tax", ("Other", ["Tax"])]),
    "Total",
]
LABELS = ["Assets.Tax", "Assets.Cash", "Liabilities.Tax", "Liabilities.Other.Tax", "Total"]
VALUES = [10, 30, 99, 7, 146, 20, 40, 199, 8, 267]


def add_items(parent, items):
    children = ET.SubElement(parent, "children")
    for item in items:
        grouped = isinstance(item, tuple)
        name = item[0] if grouped else item
        child = ET.SubElement(children, "child", {"factory-id": "group" if grouped else "item"})
        ET.SubElement(child, "name").text = name
        if grouped:
            add_items(child, item[1])


def model_xml(accounts=ACCOUNTS, values=VALUES, years=True):
    root = ET.Element("document")
    tables = ET.SubElement(ET.SubElement(root, "model"), "tables")
    table = ET.SubElement(tables, "table", oid="balance")
    ET.SubElement(table, "name").text = "Balance"
    categories = ET.SubElement(table, "categories")
    accounts_category = ET.SubElement(categories, "category", oid="accounts")
    ET.SubElement(accounts_category, "name").text = "Accounts"
    add_items(accounts_category, accounts)
    if years:
        year_category = ET.SubElement(categories, "category", oid="year")
        ET.SubElement(year_category, "name").text = "Year"
        add_items(year_category, ["2024", "2025"])
    ET.SubElement(table, "data", size=str(len(values))).text = ",".join(map(str, values))
    table = ET.SubElement(tables, "table", oid="linked")
    ET.SubElement(table, "name").text = "Linked"
    categories = ET.SubElement(table, "categories")
    ET.SubElement(categories, "category", ref="accounts")
    if years:
        ET.SubElement(categories, "category", ref="year")
    ET.SubElement(table, "data", size=str(len(values))).text = ",".join(map(str, values))
    return root


class GroupedItemsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="qx-reader-test-")
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "grouped.xmodel"
        ET.ElementTree(model_xml()).write(self.path, encoding="utf-8")
        self.model = QxModel.load(self.path)
        self.matrix = self.model.matrix("Balance")

    def export(self, fmt):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            cmd_data(self.model, "Balance", ["Year"], None, None, fmt)
        return output.getvalue()

    def select(self, name):
        return self.matrix.to_dataframe(columns=["Year"], where={"Accounts": [name]}).iloc[0].tolist()

    def test_labels_preserve_full_paths_in_document_order(self):
        self.assertEqual(self.matrix.categories[0].items, LABELS)

    def test_group_paths_do_not_change_shape_or_flat_cell_order(self):
        self.assertEqual(self.matrix.shape, (5, 2))
        self.assertEqual(self.matrix.get_data_flat(), VALUES)

    def test_qualified_paths_select_each_duplicate_leaf(self):
        for name, values in [("Assets.Tax", [10, 20]), ("Liabilities.Tax", [99, 199]),
                             ("Liabilities.Other.Tax", [7, 8])]:
            with self.subTest(name=name):
                self.assertEqual(self.select(name), values)

    def test_ambiguous_bare_name_reports_matching_paths(self):
        with self.assertRaisesRegex(ValueError, "Ambiguous.*Tax") as error:
            self.select("Tax")
        for path in ["Assets.Tax", "Liabilities.Tax", "Liabilities.Other.Tax"]:
            self.assertIn(path, str(error.exception))

    def test_unique_bare_name_remains_supported(self):
        self.assertEqual(self.select("Cash"), [30, 40])
        self.assertEqual(self.select("Cash"), self.select("Assets.Cash"))

    def test_unknown_name_lists_qualified_choices(self):
        with self.assertRaises(ValueError) as error:
            self.select("Liabilities.Missing")
        self.assertIn("not in category", str(error.exception))
        self.assertIn("Liabilities.Tax", str(error.exception))

    def test_row_labels_and_values_preserve_group_identity(self):
        frame = self.matrix.to_dataframe(columns=["Year"])
        self.assertEqual(frame.index.tolist(), LABELS)
        self.assertTrue(frame.index.is_unique)
        self.assertEqual(frame["2024"].tolist(), VALUES[:5])
        self.assertEqual(frame["2025"].tolist(), VALUES[5:])

    def test_column_labels_and_values_preserve_group_identity(self):
        frame = self.matrix.to_dataframe(columns=["Accounts"])
        self.assertEqual(frame.columns.tolist(), LABELS)
        self.assertTrue(frame.columns.is_unique)
        self.assertEqual(frame.loc["2024", "Liabilities.Tax"], 99)
        self.assertEqual(frame.loc["2025", "Liabilities.Other.Tax"], 8)

    def test_multiple_qualified_filters_preserve_requested_order(self):
        frame = self.matrix.to_dataframe(columns=["Year"],
            where={"Accounts": ["Liabilities.Tax", "Assets.Tax"]})
        self.assertEqual(frame.index.tolist(), ["Liabilities.Tax", "Assets.Tax"])
        self.assertEqual(frame["2024"].tolist(), [99, 10])

    def test_json_export_has_distinct_keys_for_duplicate_leaf_names(self):
        result = json.loads(self.export("json"))
        self.assertEqual(result["data"]["2024"], dict(zip(LABELS, VALUES[:5])))

    def test_csv_export_preserves_paths(self):
        rows = list(csv.DictReader(io.StringIO(self.export("csv"))))
        self.assertEqual([row["Accounts"] for row in rows], LABELS)

    def test_category_listing_shows_paths(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            cmd_categories(self.model, "Balance", True)
        for label in LABELS:
            self.assertIn(label, output.getvalue())

    def test_streaming_preserves_paths_with_or_without_data(self):
        for keep_data_for in ["Balance", False]:
            with self.subTest(keep_data_for=keep_data_for):
                matrix = QxModel.load(self.path, keep_data_for=keep_data_for).matrix("Balance")
                self.assertEqual(matrix.categories[0].items, LABELS)
                if keep_data_for:
                    self.assertEqual(matrix.get_data_flat(), VALUES)

    def test_linked_categories_share_paths_and_filter_resolution(self):
        linked = self.model.matrix("Linked")
        self.assertIs(linked.categories[0], self.matrix.categories[0])
        frame = linked.to_dataframe(columns=["Year"], where={"Accounts": ["Liabilities.Tax"]})
        self.assertEqual(frame.iloc[0].tolist(), [99, 199])

    def test_ungrouped_names_remain_unchanged(self):
        self.assertEqual(self.matrix.categories[1].items, ["2024", "2025"])
        self.assertEqual(self.select("Total"), [146, 267])

    def test_exact_root_label_takes_precedence_over_grouped_short_name(self):
        matrix = QxModel(model_xml(["Tax", ("Assets", ["Tax"])], [1, 2], years=False)).matrix("Balance")
        self.assertEqual(matrix.to_dataframe(where={"Accounts": ["Tax"]}).iloc[0, 0], 1)
        self.assertEqual(matrix.to_dataframe(where={"Accounts": ["Assets.Tax"]}).iloc[0, 0], 2)

    def test_literal_dots_and_apostrophes_cannot_collide_with_group_paths(self):
        accounts = [
            "Assets.Tax",
            ("Assets", ["Tax", ("Current", ["Tax"])]),
            ("Assets.Current", ["Tax"]),
            ("O'Brien", ["Owner's Tax", "A.B"]),
        ]
        labels = ["'Assets.Tax'", "Assets.Tax", "Assets.Current.Tax", "'Assets.Current'.Tax",
                  "'O''Brien'.'Owner''s Tax'", "'O''Brien'.'A.B'"]
        matrix = QxModel(model_xml(accounts, range(1, 7), years=False)).matrix("Balance")
        self.assertEqual(matrix.categories[0].items, labels)
        for value, label in enumerate(labels, start=1):
            with self.subTest(label=label):
                self.assertEqual(matrix.to_dataframe(where={"Accounts": [label]}).iloc[0, 0], value)
        self.assertEqual(matrix.to_dataframe(where={"Accounts": ["Owner's Tax"]}).iloc[0, 0], 5)

    def test_cli_accepts_a_qualified_item_path(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "data", str(self.path), "Balance",
            "--columns", "Year", "--where", "Accounts", "Liabilities.Tax", "--format", "json"],
            text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data["data"]["2024"]["0"], 99)
        self.assertEqual(data["where"], {"Accounts": ["Liabilities.Tax"]})

    def test_cli_rejects_an_ambiguous_bare_name(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "data", str(self.path), "Balance",
            "--columns", "Year", "--where", "Accounts", "Tax"], text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Ambiguous", result.stderr)
        self.assertIn("Liabilities.Tax", result.stderr)


if __name__ == "__main__":
    unittest.main()

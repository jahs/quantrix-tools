package net.jahs.quantrix.preprocessor;

import static net.jahs.quantrix.preprocessor.SelectionPreprocessor.preprocessScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link SelectionPreprocessor}, loaded from {@code tests.xml}.
 *
 * <p>Test file format — simple XML with one {@code <test>} per case:
 * <pre>{@code
 *   <test>
 *   <name>test name</name>
 *   <input>QGroovy source</input>
 *   <expected>expected Groovy output</expected>
 *   </test>
 * }</pre>
 *
 * <p>If {@code <expected>} is omitted, the expected output equals the input
 * (identity — the preprocessor should not change anything). Both
 * {@code <input>} and {@code <expected>} may be multi-line.
 *
 * <p>Run: {@code java net.jahs.quantrix.preprocessor.SelectionPreprocessorTest [tests.xml]}
 */
public class SelectionPreprocessorTest {

    public static void main(String[] args) throws IOException {
        Path testFile;
        if (args.length > 0) {
            testFile = Paths.get(args[0]);
        } else {
            testFile = Paths.get("tests/tests.xml");
            if (!Files.exists(testFile)) {
                testFile = Paths.get("tests.xml");
            }
        }

        String content = Files.readString(testFile, StandardCharsets.UTF_8);
        List<TestCase> tests = parse(content);

        int pass = 0, fail = 0;
        for (TestCase test : tests) {
            String actual = preprocessScript(test.input);
            if (actual == null ? test.expected == null : actual.equals(test.expected)) {
                System.out.println("PASS  " + test.name);
                pass++;
            } else {
                System.out.println("FAIL  " + test.name);
                System.out.println("  input:    " + show(test.input));
                System.out.println("  expected: " + show(test.expected));
                System.out.println("  actual:   " + show(actual));
                fail++;
            }
        }

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ── Test case ───────────────────────────────────────────────────

    static class TestCase {
        String name;
        String input;
        String expected;

        TestCase(String name, String input, String expected) {
            this.name = name;
            this.input = input;
            this.expected = expected != null ? expected : input;
        }
    }

    // ── Simple tag parser ───────────────────────────────────────────

    static List<TestCase> parse(String content) {
        List<TestCase> tests = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            int testStart = content.indexOf("<test>", pos);
            if (testStart < 0) break;
            int testEnd = content.indexOf("</test>", testStart);
            if (testEnd < 0) throw new IllegalArgumentException("Unclosed <test> tag");

            String block = content.substring(testStart + 6, testEnd);
            String name = extractTag(block, "name");
            String input = extractTag(block, "input");
            String expected = extractTagOrNull(block, "expected");

            if (name == null) throw new IllegalArgumentException("Missing <name> in test block");
            if (input == null) throw new IllegalArgumentException("Missing <input> in test: " + name);

            tests.add(new TestCase(name, input, expected));
            pos = testEnd + 7;
        }
        return tests;
    }

    private static String extractTag(String block, String tag) {
        String value = extractTagOrNull(block, tag);
        if (value == null) throw new IllegalArgumentException("Missing <" + tag + "> tag");
        return value;
    }

    private static String extractTagOrNull(String block, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = block.indexOf(open);
        if (start < 0) return null;
        int end = block.indexOf(close, start);
        if (end < 0) throw new IllegalArgumentException("Unclosed <" + tag + "> tag");
        return block.substring(start + open.length(), end);
    }

    /** Show a string with newlines escaped for display. */
    private static String show(String s) {
        if (s == null) return "<null>";
        return s.replace("\n", "\\n");
    }
}

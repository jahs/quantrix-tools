package net.jahs.quantrix.preprocessor;

/**
 * Rewrites Quantrix pipe selection syntax {@code |...|} to
 * {@code getSelection("...")} method calls.
 *
 * <p>The Quantrix scripting console supports a shorthand where
 * {@code |Balance Sheet::Cash:2020|} is rewritten to
 * {@code getSelection("Balance Sheet::Cash:2020")} before compilation.
 * The built-in implementation uses a regex that operates on raw source text,
 * which causes incorrect behaviour when pipes appear inside string literals,
 * comments, or GString interpolation blocks, and mishandles double-quoted
 * names within the pipe expression.
 *
 * <p>This implementation uses a forward-scanning state machine that
 * tracks the last significant token type and paren depth — the same
 * disambiguation strategy the GroovyLexer uses for {@code /} (regex vs
 * division). Specifically:
 * <ul>
 *   <li>After an expression-ending token (identifier, number, literal,
 *       {@code )}, {@code ]}, {@code }}, {@code ++}, {@code --},
 *       {@code this}, {@code true}, etc.), {@code |} is binary OR</li>
 *   <li>Otherwise (after operators, keywords like {@code return},
 *       opening brackets, start of source), {@code |} is a pipe
 *       delimiter</li>
 *   <li>Newlines reset the token context (new statement), except inside
 *       {@code ()} and {@code []} where newlines are insignificant
 *       — matching Groovy's own rule</li>
 *   <li>Correctly skips all string literal types including GStrings,
 *       with pipe transformation inside {@code ${...}} interpolation</li>
 *   <li>Correctly skips line and block comments</li>
 * </ul>
 *
 * <h3>Pipe syntax</h3>
 * <pre>
 *   |view::item:item|           →  getSelection("view::item:item")
 *   |Matrix::'Item Name':2020| →  getSelection("Matrix::'Item Name':2020")
 * </pre>
 *
 * <p>Within pipe content, single quotes delimit names containing special
 * characters, following Quantrix's standard quoting convention.
 * A literal single quote within a quoted name is doubled: {@code ''}.
 * Pipe expressions cannot span lines.
 *
 * <h3>Quoting within pipe content</h3>
 * <pre>
 *   |Matrix::'Item Name':2020|         →  getSelection("Matrix::'Item Name':2020")
 *   |Matrix::'Name|Here'|              →  getSelection("Matrix::'Name|Here'")
 *   |Matrix::'Shareholder''s Equity'|  →  getSelection("Matrix::'Shareholder''s Equity'")
 * </pre>
 *
 * <h3>Disambiguation</h3>
 * <p>Uses the same strategy as the Groovy lexer's {@code isRegexAllowed()}:
 * a {@code |} is a pipe delimiter unless the previous significant token
 * is expression-ending. This matches the set from
 * {@code GroovyLexer.REGEX_CHECK_ARRAY}: identifiers, numbers, string
 * literals, boolean/null literals, {@code this}, {@code super},
 * closing brackets ({@code )}, {@code ]}, {@code }}), and
 * {@code ++}/{@code --}.
 *
 * <h3>Usage</h3>
 * <pre>
 *   String groovy = SelectionPreprocessor.preprocessScript(qgroovySource);
 *   // groovy is now valid Groovy — compile and execute normally
 * </pre>
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Shebang lines ({@code #!}) are not specially handled — a pipe
 *       in a shebang would be incorrectly matched. Shebangs are only
 *       valid on line 1 and rarely contain {@code |}.</li>
 * </ul>
 */
public class SelectionPreprocessor {

    /** After these token types, | is binary OR (not a pipe delimiter). */
    private static final int EXPR_END = 1;
    /** After non-expression tokens (operators, keywords, open brackets), | is a pipe delimiter. */
    private static final int PIPE_ALLOWED = 0;
    /** After '.', so the next identifier is a member name (even if it's a keyword). */
    private static final int AFTER_DOT = 2;

    /** Expression-ending keywords — these behave like identifiers. */
    private static final java.util.Set<String> EXPR_END_KEYWORDS = java.util.Set.of(
        "this", "super", "true", "false", "null"
    );

    private final String src;
    private final int len;
    private final StringBuilder out;
    private int pos;
    private int lastToken;  // EXPR_END, PIPE_ALLOWED, or AFTER_DOT
    private int parenDepth; // depth of () and [] only — not {}

    /**
     * Preprocess a QGroovy source string, rewriting pipe selection syntax
     * to {@code getSelection()} calls. Returns the input unchanged if it
     * contains no pipe characters.
     *
     * <p>Drop-in replacement for
     * {@code com.quantrix.scripting.core.internal.AutoSelectionParser.preprocessScript()}.
     */
    public static String preprocessScript(String source) {
        if (source == null || source.indexOf('|') < 0) return source;
        return new SelectionPreprocessor(source).process();
    }

    private SelectionPreprocessor(String source) {
        this.src = source;
        this.len = source.length();
        this.out = new StringBuilder(len + 64);
        this.pos = 0;
        this.lastToken = PIPE_ALLOWED;
        this.parenDepth = 0;
    }

    private String process() {
        processCode(/* closingChar */ -1);
        return out.toString();
    }

    // ── Core loop ───────────────────────────────────────────────────

    /**
     * Process source as Groovy code, transforming pipe expressions.
     * Stops when it hits {@code closingChar} (used for GString interpolation
     * where closingChar is '}') or end of source. The closing character
     * itself is NOT consumed.
     */
    private void processCode(int closingChar) {
        int braceDepth = 0;

        while (pos < len) {
            char c = src.charAt(pos);

            // Brace tracking for GString interpolation: ${...} may contain
            // nested braces (closures, maps) that are not our closing brace.
            if (closingChar == '}') {
                if (c == '}' && braceDepth == 0) return;
                if (c == '{') {
                    braceDepth++;
                    out.append(c);
                    pos++;
                    lastToken = PIPE_ALLOWED;
                    continue;
                }
                if (c == '}') {
                    braceDepth--;
                    out.append(c);
                    pos++;
                    lastToken = EXPR_END;
                    continue;
                }
            }

            if (trySkipCommentOrSlashy()) continue;
            if (trySkipDollarSlashy()) { lastToken = EXPR_END; continue; }
            if (trySkipString()) { lastToken = EXPR_END; continue; }

            if (c == '|') {
                handlePipe();
                continue;
            }

            // ── Track token types ───────────────────────────────────

            if (c == '(' || c == '[') {
                parenDepth++;
                out.append(c);
                pos++;
                lastToken = PIPE_ALLOWED;
                continue;
            }

            if (c == ')' || c == ']') {
                parenDepth = Math.max(0, parenDepth - 1);
                out.append(c);
                pos++;
                lastToken = EXPR_END;
                continue;
            }

            if (c == '{') {
                // { does NOT increment parenDepth — newlines are significant
                // inside {} blocks (unlike () and [])
                out.append(c);
                pos++;
                lastToken = PIPE_ALLOWED;
                continue;
            }

            if (c == '}') {
                out.append(c);
                pos++;
                lastToken = EXPR_END;
                continue;
            }

            if (c == '+' && pos + 1 < len && src.charAt(pos + 1) == '+') {
                out.append("++");
                pos += 2;
                lastToken = EXPR_END; // postfix ++ (or prefix — same as GroovyLexer)
                continue;
            }

            if (c == '-' && pos + 1 < len && src.charAt(pos + 1) == '-') {
                out.append("--");
                pos += 2;
                lastToken = EXPR_END;
                continue;
            }

            if (Character.isJavaIdentifierStart(c)) {
                int start = pos;
                while (pos < len && Character.isJavaIdentifierPart(src.charAt(pos))) pos++;
                String word = src.substring(start, pos);
                out.append(word);
                if (EXPR_END_KEYWORDS.contains(word) || !isKeyword(word) || lastToken == AFTER_DOT) {
                    lastToken = EXPR_END;
                } else {
                    lastToken = PIPE_ALLOWED;
                }
                continue;
            }

            if (Character.isDigit(c) || (c == '.' && pos + 1 < len && Character.isDigit(src.charAt(pos + 1)))) {
                skipNumber();
                lastToken = EXPR_END;
                continue;
            }

            // Newline handling: significant only outside () and []
            if (c == '\n') {
                out.append(c);
                pos++;
                // AFTER_DOT survives newlines — obj.\nclass is member access
                if (parenDepth == 0 && lastToken != AFTER_DOT) lastToken = PIPE_ALLOWED;
                continue;
            }

            if (c == '\r') {
                out.append(c);
                pos++;
                continue;
            }

            // Line continuation: \ before newline
            if (c == '\\' && pos + 1 < len) {
                char next = src.charAt(pos + 1);
                if (next == '\n') {
                    out.append(c);
                    out.append(next);
                    pos += 2;
                    // Don't reset lastToken — line continues
                    continue;
                }
                if (next == '\r') {
                    out.append(c);
                    out.append(next);
                    pos += 2;
                    // Also consume \n after \r if present (\r\n)
                    if (pos < len && src.charAt(pos) == '\n') {
                        out.append(src.charAt(pos));
                        pos++;
                    }
                    continue;
                }
            }

            // Whitespace — don't change lastToken
            if (c == ' ' || c == '\t') {
                out.append(c);
                pos++;
                continue;
            }

            // Semicolons reset context (explicit statement separator)
            if (c == ';') {
                out.append(c);
                pos++;
                lastToken = PIPE_ALLOWED;
                continue;
            }

            // All other characters (operators, punctuation) → PIPE_ALLOWED
            // '.' sets AFTER_DOT so that a following keyword (even across
            // comments/newlines) is treated as a member name.
            out.append(c);
            pos++;
            lastToken = (c == '.') ? AFTER_DOT : PIPE_ALLOWED;
        }
    }

    /**
     * Process a GString interpolation block {@code ${...}}. Emits the
     * delimiters, recurses into {@link #processCode} with fresh context,
     * then restores the outer scanner state. The caller must have already
     * verified that {@code ${} is at {@code pos}.
     */
    private void processInterpolation() {
        out.append('$');
        out.append('{');
        pos += 2;
        int savedLastToken = lastToken;
        int savedParenDepth = parenDepth;
        lastToken = PIPE_ALLOWED;
        parenDepth = 0;
        processCode(/* closingChar */ '}');
        lastToken = savedLastToken;
        parenDepth = savedParenDepth;
        if (pos < len && src.charAt(pos) == '}') {
            out.append('}');
            pos++;
        }
    }

    // ── Pipe handling ───────────────────────────────────────────────

    private void handlePipe() {
        // || is logical OR
        if (pos + 1 < len && src.charAt(pos + 1) == '|') {
            out.append("||");
            pos += 2;
            lastToken = PIPE_ALLOWED;
            return;
        }

        // |= is bitwise OR assignment
        if (pos + 1 < len && src.charAt(pos + 1) == '=') {
            out.append("|=");
            pos += 2;
            lastToken = PIPE_ALLOWED;
            return;
        }

        // After an expression-ending token, | is binary OR
        if (lastToken == EXPR_END) {
            out.append('|');
            pos++;
            lastToken = PIPE_ALLOWED;
            return;
        }

        // Opening pipe — scan for closing |
        pos++; // skip opening |

        // Pipe content cannot start with whitespace — matches the built-in
        // regex's (?!\s) and the grammar's unquoted_start rule.
        if (pos < len && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            out.append('|');
            lastToken = PIPE_ALLOWED;
            return;
        }

        int rewindTo = pos;

        StringBuilder content = new StringBuilder();
        boolean found = scanPipeContent(content);

        if (!found) {
            // No closing pipe on this line — treat as literal |
            pos = rewindTo;
            out.append('|');
            lastToken = PIPE_ALLOWED;
            return;
        }

        out.append("getSelection(\"");
        escapeIntoString(content, out);
        out.append("\")");
        lastToken = EXPR_END; // the getSelection(...) call is expression-ending
    }

    /**
     * Scan pipe content until closing {@code |}. Handles single-quoted
     * names within the pipe expression — names containing special
     * characters (including {@code |} itself) must be single-quoted,
     * following Quantrix's standard quoting convention.
     *
     * <p>A literal single quote within a quoted name is represented by
     * doubling: {@code ''} → {@code '}.
     *
     * <p>Returns true if a closing pipe was found (and pos is advanced
     * past it), false otherwise.
     */
    private boolean scanPipeContent(StringBuilder content) {
        while (pos < len) {
            char c = src.charAt(pos);

            if (c == '|') {
                pos++; // skip closing |
                return true;
            }

            if (c == '\n') {
                return false; // pipe expressions don't span lines
            }

            if (c == '\'') {
                // Single-quoted name within pipe content
                content.append(c);
                pos++;
                while (pos < len && src.charAt(pos) != '\'' && src.charAt(pos) != '\n') {
                    content.append(src.charAt(pos));
                    pos++;
                }
                if (pos < len && src.charAt(pos) == '\'') {
                    content.append('\'');
                    pos++;
                }
                continue;
            }

            content.append(c);
            pos++;
        }
        return false; // EOF without closing pipe
    }

    // ── Keyword classification ──────────────────────────────────────

    /**
     * Groovy keywords that are NOT expression-ending. When one of these
     * precedes {@code |}, the pipe is a selection delimiter.
     */
    private static boolean isKeyword(String word) {
        switch (word) {
            case "abstract": case "as": case "assert": case "break":
            case "case": case "catch": case "class": case "const":
            case "continue": case "def": case "default": case "do":
            case "else": case "enum": case "extends": case "final":
            case "finally": case "for": case "goto": case "if":
            case "implements": case "import": case "in": case "instanceof":
            case "interface": case "native": case "new": case "package":
            case "private": case "protected": case "public":
            case "return": case "static": case "strictfp":
            case "switch": case "synchronized": case "throw": case "throws":
            case "trait": case "transient": case "try": case "var":
            case "void": case "volatile": case "while": case "yield":
                return true;
            default:
                return false;
        }
    }

    // ── Number scanning ─────────────────────────────────────────────

    private void skipNumber() {
        char c = src.charAt(pos);

        // Hex, octal, binary: 0x..., 0b..., 0...
        if (c == '0' && pos + 1 < len) {
            char next = src.charAt(pos + 1);
            if (next == 'x' || next == 'X') {
                out.append(c); out.append(next); pos += 2;
                while (pos < len && isHexDigitOrUnderscore(src.charAt(pos))) {
                    out.append(src.charAt(pos)); pos++;
                }
                skipNumberSuffix();
                return;
            }
            if (next == 'b' || next == 'B') {
                out.append(c); out.append(next); pos += 2;
                while (pos < len && isBinaryDigitOrUnderscore(src.charAt(pos))) {
                    out.append(src.charAt(pos)); pos++;
                }
                skipNumberSuffix();
                return;
            }
        }

        // Decimal (possibly floating point)
        while (pos < len && isDecimalDigitOrUnderscore(src.charAt(pos))) {
            out.append(src.charAt(pos)); pos++;
        }
        // Decimal point
        if (pos < len && src.charAt(pos) == '.' && pos + 1 < len && Character.isDigit(src.charAt(pos + 1))) {
            out.append('.'); pos++;
            while (pos < len && isDecimalDigitOrUnderscore(src.charAt(pos))) {
                out.append(src.charAt(pos)); pos++;
            }
        }
        // Exponent
        if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            out.append(src.charAt(pos)); pos++;
            if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                out.append(src.charAt(pos)); pos++;
            }
            while (pos < len && isDecimalDigitOrUnderscore(src.charAt(pos))) {
                out.append(src.charAt(pos)); pos++;
            }
        }
        skipNumberSuffix();
    }

    private void skipNumberSuffix() {
        if (pos < len) {
            char c = src.charAt(pos);
            if (c == 'l' || c == 'L' || c == 'i' || c == 'I'
                || c == 'g' || c == 'G' || c == 'f' || c == 'F'
                || c == 'd' || c == 'D') {
                out.append(c); pos++;
            }
        }
    }

    private static boolean isHexDigitOrUnderscore(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
            || (c >= 'A' && c <= 'F') || c == '_';
    }

    private static boolean isBinaryDigitOrUnderscore(char c) {
        return c == '0' || c == '1' || c == '_';
    }

    private static boolean isDecimalDigitOrUnderscore(char c) {
        return (c >= '0' && c <= '9') || c == '_';
    }

    // ── Comment and slashy string skipping ────────────────────────────

    /**
     * Handle {@code /} — could be a comment ({@code //}, {@code /*}),
     * a slashy string ({@code /pattern/}), or division. Uses the same
     * {@code isRegexAllowed()} logic as the GroovyLexer: if {@code /}
     * follows an expression-ending token, it's division; otherwise it's
     * a slashy string delimiter.
     *
     * <p>Returns true if a comment or slashy string was consumed.
     */
    private boolean trySkipCommentOrSlashy() {
        if (src.charAt(pos) != '/') return false;
        if (pos + 1 >= len) return false;

        char next = src.charAt(pos + 1);

        // // line comment — typed as NL in GroovyLexer
        if (next == '/') {
            while (pos < len && src.charAt(pos) != '\n') {
                out.append(src.charAt(pos));
                pos++;
            }
            // Line comment acts as newline (statement separator outside parens).
            // AFTER_DOT survives — obj.//comment\nclass is member access.
            if (parenDepth == 0 && lastToken != AFTER_DOT) lastToken = PIPE_ALLOWED;
            return true;
        }

        // /* block comment */ — typed as NL in GroovyLexer, but only
        // acts as a statement separator when outside parens AND followed
        // by only whitespace until end of line (ignoreMultiLineCommentConditionally).
        // Inline block comments (e.g. x /* ... */ + y) are hidden.
        if (next == '*') {
            out.append('/');
            out.append('*');
            pos += 2;
            while (pos < len) {
                if (src.charAt(pos) == '*' && pos + 1 < len && src.charAt(pos + 1) == '/') {
                    out.append('*');
                    out.append('/');
                    pos += 2;
                    if (parenDepth == 0 && isFollowedByWhitespaceOnly()
                            && lastToken != AFTER_DOT) {
                        lastToken = PIPE_ALLOWED;
                    }
                    return true;
                }
                out.append(src.charAt(pos));
                pos++;
            }
            return true; // unterminated
        }

        // /slashy string/ — only if regex/slashy is allowed here
        if (lastToken != EXPR_END) {
            skipSlashyString();
            lastToken = EXPR_END;
            return true;
        }

        return false; // it's division — let the main loop handle it
    }

    /**
     * Check if only whitespace remains until end of line (or EOF).
     * Used to determine if a block comment acts as a newline,
     * matching GroovyLexer's ignoreMultiLineCommentConditionally().
     */
    private boolean isFollowedByWhitespaceOnly() {
        int i = pos;
        while (i < len) {
            char c = src.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (c != ' ' && c != '\t') return false;
            i++;
        }
        return true; // EOF counts as end of line
    }

    /**
     * Skip a slashy string: {@code /content/}. Handles {@code \/} escapes
     * and {@code ${...}} GString interpolation.
     */
    private void skipSlashyString() {
        out.append('/');
        pos++; // skip opening /

        while (pos < len) {
            char c = src.charAt(pos);

            // \/ escape (escaped slash)
            if (c == '\\' && pos + 1 < len && src.charAt(pos + 1) == '/') {
                out.append('\\');
                out.append('/');
                pos += 2;
                continue;
            }

            // Closing /
            if (c == '/') {
                out.append('/');
                pos++;
                return;
            }

            if (c == '$' && pos + 1 < len && src.charAt(pos + 1) == '{') {
                processInterpolation();
                continue;
            }

            out.append(c);
            pos++;
        }
    }

    /**
     * Handle dollar-slashy strings: {@code $/content/$}. Unlike slashy
     * strings, Groovy recognises {@code $/} as a dollar-slashy opener
     * regardless of the preceding token — the {@code isRegexAllowed()}
     * check does not apply. The only case where {@code $/} is NOT an
     * opener is when {@code $} is immediately adjacent to a preceding
     * identifier (e.g. {@code foo$/.../$}), but that is already handled
     * by the identifier scanner consuming the {@code $} as part of the
     * identifier before this method is reached.
     *
     * <p>Escape sequences: {@code $$} for literal {@code $},
     * {@code $/} for literal {@code /}, {@code $/$} for literal
     * {@code /$}.
     */
    private boolean trySkipDollarSlashy() {
        if (src.charAt(pos) != '$') return false;
        if (pos + 1 >= len || src.charAt(pos + 1) != '/') return false;

        out.append('$');
        out.append('/');
        pos += 2; // skip $/

        while (pos < len) {
            char c = src.charAt(pos);

            // Closing /$
            if (c == '/' && pos + 1 < len && src.charAt(pos + 1) == '$') {
                out.append('/');
                out.append('$');
                pos += 2;
                return true;
            }

            // $$ escape (literal $)
            if (c == '$' && pos + 1 < len && src.charAt(pos + 1) == '$') {
                out.append('$');
                out.append('$');
                pos += 2;
                continue;
            }

            // $/$ escape (literal /$) — only if not followed by end-of-string
            if (c == '$' && pos + 1 < len && src.charAt(pos + 1) == '/'
                    && pos + 2 < len && src.charAt(pos + 2) == '$') {
                // Check: is this $/ followed by $ that closes the string?
                // $/$$ means: escape $/$ then $. But $/$ at the end is the closer.
                // The GroovyLexer rule: DollarSlashDollarEscape matches $/$
                // only when the char before is not $. We simplify: consume $/$.
                out.append('$');
                out.append('/');
                out.append('$');
                pos += 3;
                continue;
            }

            // $/ escape (literal /) — only when not followed by $
            if (c == '$' && pos + 1 < len && src.charAt(pos + 1) == '/') {
                // Already checked for /$ closing above, so this is mid-string
                out.append('$');
                out.append('/');
                pos += 2;
                continue;
            }

            if (c == '$' && pos + 1 < len && src.charAt(pos + 1) == '{') {
                processInterpolation();
                continue;
            }

            out.append(c);
            pos++;
        }
        return true; // unterminated
    }

    // ── String skipping ─────────────────────────────────────────────

    /** Returns true if a string was skipped. Caller sets lastToken = EXPR_END. */
    private boolean trySkipString() {
        char c = src.charAt(pos);
        if (c != '\'' && c != '"') return false;

        // Triple-quoted?
        if (pos + 2 < len && src.charAt(pos + 1) == c && src.charAt(pos + 2) == c) {
            skipTripleString(c);
            return true;
        }

        skipSimpleString(c);
        return true;
    }

    private void skipSimpleString(char quote) {
        boolean isGString = (quote == '"');
        out.append(quote);
        pos++;

        while (pos < len) {
            char c = src.charAt(pos);

            if (c == '\\' && pos + 1 < len) {
                out.append(c);
                out.append(src.charAt(pos + 1));
                pos += 2;
                continue;
            }

            if (c == quote) {
                out.append(c);
                pos++;
                return;
            }

            if (isGString && c == '$' && pos + 1 < len && src.charAt(pos + 1) == '{') {
                processInterpolation();
                continue;
            }

            // Simple strings (both ' and ") cannot span lines
            if (c == '\n') {
                out.append(c);
                pos++;
                return;
            }

            out.append(c);
            pos++;
        }
    }

    private void skipTripleString(char quote) {
        boolean isGString = (quote == '"');
        out.append(quote);
        out.append(quote);
        out.append(quote);
        pos += 3;

        while (pos < len) {
            char c = src.charAt(pos);

            if (c == '\\' && pos + 1 < len) {
                out.append(c);
                out.append(src.charAt(pos + 1));
                pos += 2;
                continue;
            }

            if (c == quote && pos + 2 < len
                    && src.charAt(pos + 1) == quote && src.charAt(pos + 2) == quote) {
                out.append(quote);
                out.append(quote);
                out.append(quote);
                pos += 3;
                return;
            }

            if (isGString && c == '$' && pos + 1 < len && src.charAt(pos + 1) == '{') {
                processInterpolation();
                continue;
            }

            out.append(c);
            pos++;
        }
    }

    // ── String escaping ─────────────────────────────────────────────

    /**
     * Escape pipe content for embedding inside a double-quoted Groovy
     * string literal. Escapes {@code \}, {@code "}, and {@code $}
     * characters. The {@code $} escape is critical — without it,
     * {@code getSelection("Matrix::$Name")} would be compiled as a
     * GString with interpolation of {@code $Name}.
     */
    private static void escapeIntoString(StringBuilder content, StringBuilder dest) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\') { dest.append('\\').append('\\'); }
            else if (c == '"') { dest.append('\\').append('"'); }
            else if (c == '$') { dest.append('\\').append('$'); }
            else { dest.append(c); }
        }
    }
}


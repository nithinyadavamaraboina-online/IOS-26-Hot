package com.realme.samurai.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Clean-room recursive-descent scientific & arithmetic expression parser (`MathEngine`)
 * with iOS 26 percentage semantics, Trig Deg/Rad switching, implicit multiplication, and
 * 12-significant-digit BigDecimal noise elimination.
 */
public final class MathEngine {

    private static final MathContext DISPLAY_MC = new MathContext(12, RoundingMode.HALF_UP);

    private MathEngine() {}

    public static final class EvalResult {
        public final boolean valid;
        public final double value;
        public final String formatted;
        public final String errorMessage;

        private EvalResult(boolean valid, double value, String formatted, String errorMessage) {
            this.valid = valid;
            this.value = value;
            this.formatted = formatted;
            this.errorMessage = errorMessage;
        }

        public static EvalResult ok(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return error("Error");
            }
            return new EvalResult(true, v, formatNumber(v), null);
        }

        public static EvalResult error(String msg) {
            return new EvalResult(false, Double.NaN, "Error", msg);
        }
    }

    public static EvalResult evaluate(String rawExpression, boolean degreesMode) {
        if (rawExpression == null) {
            return EvalResult.error("Empty");
        }
        String cleaned = normalizeExpression(rawExpression);
        if (cleaned.isEmpty()) {
            return EvalResult.error("Empty");
        }
        // Strip trailing binary operators for live preview / graceful evaluation
        while (!cleaned.isEmpty()) {
            char tail = cleaned.charAt(cleaned.length() - 1);
            if (tail == '+' || tail == '-' || tail == '*' || tail == '/' || tail == '^' || tail == '.') {
                if (tail == '.') break;
                if (cleaned.length() == 1) return EvalResult.error("Incomplete");
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            } else {
                break;
            }
        }
        // Auto-balance unclosed '(' parentheses
        int openCount = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '(') openCount++;
            else if (c == ')' && openCount > 0) openCount--;
        }
        if (openCount > 0) {
            StringBuilder sb = new StringBuilder(cleaned);
            for (int i = 0; i < openCount; i++) {
                sb.append(')');
            }
            cleaned = sb.toString();
        }

        try {
            Parser parser = new Parser(cleaned, degreesMode);
            double result = parser.parseFull();
            return EvalResult.ok(result);
        } catch (IllegalArgumentException ex) {
            return EvalResult.error(ex.getMessage());
        }
    }

    private static String normalizeExpression(String expr) {
        return expr
                .replace(",", "")
                .replace(" ", "")
                .replace("\u00D7", "*")   // ×
                .replace("\u00F7", "/")   // ÷
                .replace("\u2212", "-")   // −
                .replace("log\u2081\u2080(", "log(")
                .replace("\u221A(", "sqrt(")
                .replace("\u221A", "sqrt");
    }

    public static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "Error";
        }
        if (Math.abs(value) < 1e-13) {
            return "0";
        }
        double abs = Math.abs(value);
        if (abs >= 1e12 || (abs > 0.0 && abs < 1e-7)) {
            DecimalFormat sci = new DecimalFormat("0.######E0", DecimalFormatSymbols.getInstance(Locale.US));
            return sci.format(value).replace("E", "e");
        }
        BigDecimal rounded = BigDecimal.valueOf(value).round(DISPLAY_MC).stripTrailingZeros();
        String plain = rounded.toPlainString();
        return addThousandsCommasToPlainNumber(plain);
    }

    public static String formatExpressionPreview(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length() + 12);
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (Character.isDigit(c)) {
                int start = i;
                boolean hasDot = false;
                while (i < n && (Character.isDigit(raw.charAt(i)) || (raw.charAt(i) == '.' && !hasDot))) {
                    if (raw.charAt(i) == '.') hasDot = true;
                    i++;
                }
                String token = raw.substring(start, i);
                out.append(addThousandsCommasToPlainNumber(token));
            } else if (c == '\u00D7' || c == '\u00F7' || c == '+' || c == '\u2212') {
                // Binary operator spacing unless unary minus after '(' or start
                boolean unary = (c == '\u2212' || c == '+')
                        && (i == 0 || raw.charAt(i - 1) == '(' || raw.charAt(i - 1) == '^');
                if (unary) {
                    out.append(c);
                } else {
                    out.append(' ').append(c).append(' ');
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().trim();
    }

    private static String addThousandsCommasToPlainNumber(String plain) {
        if (plain == null || plain.isEmpty() || plain.indexOf('e') >= 0 || plain.indexOf('E') >= 0) {
            return plain;
        }
        boolean neg = plain.startsWith("-");
        String unsigned = neg ? plain.substring(1) : plain;
        int dotIdx = unsigned.indexOf('.');
        String intPart = (dotIdx >= 0) ? unsigned.substring(0, dotIdx) : unsigned;
        String fracPart = (dotIdx >= 0) ? unsigned.substring(dotIdx) : "";
        if (intPart.length() <= 3) {
            return (neg ? "\u2212" : "") + intPart + fracPart;
        }
        StringBuilder sb = new StringBuilder(unsigned.length() + 6);
        if (neg) sb.append('\u2212');
        int firstGroup = intPart.length() % 3;
        if (firstGroup > 0) {
            sb.append(intPart, 0, firstGroup);
        }
        for (int idx = firstGroup; idx < intPart.length(); idx += 3) {
            if (idx > 0) sb.append(',');
            sb.append(intPart, idx, idx + 3);
        }
        sb.append(fracPart);
        return sb.toString();
    }

    private static final class TermVal {
        final double value;
        final boolean purePercent;

        TermVal(double value, boolean purePercent) {
            this.value = value;
            this.purePercent = purePercent;
        }
    }

    private static final class Parser {
        private final String s;
        private final boolean deg;
        private int pos = 0;

        Parser(String s, boolean deg) {
            this.s = s;
            this.deg = deg;
        }

        double parseFull() {
            double v = parseAddSub();
            if (pos < s.length()) {
                throw new IllegalArgumentException("Unexpected token at " + pos);
            }
            return v;
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private boolean match(char expected) {
            if (peek() == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private double parseAddSub() {
            TermVal leftTerm = parseMulDiv();
            double acc = leftTerm.value;
            while (pos < s.length()) {
                char c = peek();
                if (c == '+' || c == '-') {
                    pos++;
                    TermVal rightTerm = parseMulDiv();
                    double rhs = rightTerm.purePercent ? (acc * rightTerm.value) : rightTerm.value;
                    acc = (c == '+') ? (acc + rhs) : (acc - rhs);
                } else {
                    break;
                }
            }
            return acc;
        }

        private TermVal parseMulDiv() {
            TermVal first = parseUnary();
            double acc = first.value;
            boolean purePct = first.purePercent;

            while (pos < s.length()) {
                char c = peek();
                if (c == '*' || c == '/') {
                    pos++;
                    purePct = false;
                    TermVal rhs = parseUnary();
                    if (c == '*') {
                        acc *= rhs.value;
                    } else {
                        if (Math.abs(rhs.value) < 1e-15) {
                            throw new IllegalArgumentException("Divide by zero");
                        }
                        acc /= rhs.value;
                    }
                } else if (startsImplicitFactor(c)) {
                    purePct = false;
                    TermVal rhs = parseUnary();
                    acc *= rhs.value;
                } else {
                    break;
                }
            }
            return new TermVal(acc, purePct);
        }

        private boolean startsImplicitFactor(char c) {
            return c == '(' || c == '\u03C0' || Character.isLetter(c);
        }

        private TermVal parseUnary() {
            if (match('+')) {
                return parseUnary();
            }
            if (match('-')) {
                TermVal inner = parseUnary();
                return new TermVal(-inner.value, inner.purePercent);
            }
            return parsePower();
        }

        private TermVal parsePower() {
            TermVal base = parsePostfix();
            if (match('^')) {
                TermVal exp = parseUnary();
                double res = Math.pow(base.value, exp.value);
                return new TermVal(res, false);
            }
            return base;
        }

        private TermVal parsePostfix() {
            double val = parsePrimary();
            boolean pct = false;
            while (pos < s.length()) {
                if (match('%')) {
                    val /= 100.0;
                    pct = true;
                } else if (match('\u00B2')) { // ²
                    val = val * val;
                    pct = false;
                } else {
                    break;
                }
            }
            return new TermVal(val, pct);
        }

        private double parsePrimary() {
            if (match('(')) {
                if (match(')')) {
                    return 0.0;
                }
                double inner = parseAddSub();
                match(')');
                return inner;
            }
            if (match('\u03C0')) { // π
                return Math.PI;
            }
            if (peek() == 'e' && (pos + 1 >= s.length() || !Character.isLetter(s.charAt(pos + 1)))) {
                pos++;
                return Math.E;
            }
            if (Character.isLetter(peek())) {
                int start = pos;
                while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                    pos++;
                }
                String fn = s.substring(start, pos);
                double arg;
                if (match('(')) {
                    arg = parseAddSub();
                    match(')');
                } else {
                    arg = parseUnary().value;
                }
                return applyFunction(fn, arg);
            }
            int start = pos;
            boolean hasDot = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !hasDot) {
                    hasDot = true;
                    pos++;
                } else {
                    break;
                }
            }
            // Scientific exponent e.g. 1.5e6
            if (pos > start && pos + 1 < s.length() && (s.charAt(pos) == 'E')) {
                pos++;
                if (s.charAt(pos) == '+' || s.charAt(pos) == '-') pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Expected number at " + pos);
            }
            String numStr = s.substring(start, pos);
            if (".".equals(numStr)) return 0.0;
            return Double.parseDouble(numStr);
        }

        private double applyFunction(String fn, double x) {
            switch (fn) {
                case "sin": {
                    if (deg) {
                        double mod = ((x % 360.0) + 360.0) % 360.0;
                        if (Math.abs(mod) < 1e-11 || Math.abs(mod - 180.0) < 1e-11) return 0.0;
                        return Math.sin(Math.toRadians(x));
                    }
                    return Math.sin(x);
                }
                case "cos": {
                    if (deg) {
                        double mod = ((x % 360.0) + 360.0) % 360.0;
                        if (Math.abs(mod - 90.0) < 1e-11 || Math.abs(mod - 270.0) < 1e-11) return 0.0;
                        return Math.cos(Math.toRadians(x));
                    }
                    return Math.cos(x);
                }
                case "tan": {
                    if (deg) {
                        double mod = ((x % 180.0) + 180.0) % 180.0;
                        if (Math.abs(mod - 90.0) < 1e-9) {
                            throw new IllegalArgumentException("Undefined tan(90)");
                        }
                        if (Math.abs(mod) < 1e-11) return 0.0;
                        return Math.tan(Math.toRadians(x));
                    }
                    return Math.tan(x);
                }
                case "ln":
                    if (x <= 0.0) throw new IllegalArgumentException("ln domain");
                    return Math.log(x);
                case "log":
                    if (x <= 0.0) throw new IllegalArgumentException("log10 domain");
                    return Math.log10(x);
                case "sqrt":
                    if (x < 0.0) throw new IllegalArgumentException("sqrt domain");
                    return Math.sqrt(x);
                default:
                    throw new IllegalArgumentException("Unknown function: " + fn);
            }
        }
    }
}

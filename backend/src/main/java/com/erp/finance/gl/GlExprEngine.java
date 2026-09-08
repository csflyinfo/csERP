package com.erp.finance.gl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 总账凭证模板表达式引擎（自研，零依赖，禁止 SpEL/JS/反射）。
 * 白名单：数字、字符串、payload 变量、true/false/null、+ - * / %、括号、一元负号/逻辑非、
 * 比较（== != &gt; &gt;= &lt; &lt;=）、逻辑（&amp;&amp; ||）。不支持函数调用、成员访问（点号）。
 * 金额结果 BigDecimal HALF_UP 2 位；除零/未知变量/非法语法一律抛中文错误。
 */
public final class GlExprEngine {

    private GlExprEngine() {}

    /** 表达式错误属于业务校验类错误，消息（中文）直接回传给前端，故继承 IllegalArgumentException。 */
    public static class ExprException extends IllegalArgumentException {
        public ExprException(String msg) { super(msg); }
    }

    /** 求值金额表达式，结果保留 2 位；null/空 → 0。 */
    public static BigDecimal evalAmount(String expr, Map<String, Object> vars) {
        if (expr == null || expr.trim().isEmpty()) return BigDecimal.ZERO;
        Object v = new Parser(expr, vars).parse();
        if (v == null) return BigDecimal.ZERO;
        BigDecimal bd = toBd(v);
        return bd.setScale(2, RoundingMode.HALF_UP);
    }

    /** 求值条件表达式（严格模式：变量缺失即报错，用于转账/报表公式校验）；空条件视为成立。 */
    public static boolean evalCondition(String expr, Map<String, Object> vars) {
        return evalCondition(expr, vars, false);
    }

    /**
     * 求值条件表达式。
     * @param lenient 宽松模式：业务凭证模板的 payload 是稀疏的（收款单客户/员工/供应商三选一），
     *                条件里 {@code customer_code == ''} 判断"某字段为空"时该字段本就不存在，
     *                宽松模式把缺失变量视为空串；严格模式用于人工配置的转账/报表公式，
     *                写错变量名必须报错提示。
     */
    public static boolean evalCondition(String expr, Map<String, Object> vars, boolean lenient) {
        if (expr == null || expr.trim().isEmpty()) return true;
        Object v = new Parser(expr, vars, lenient).parse();
        if (!(v instanceof Boolean))
            throw new ExprException("条件表达式结果不是布尔值：" + expr);
        return (Boolean) v;
    }

    // ---------------- 词法 ----------------

    private static final int T_NUM = 1, T_STR = 2, T_ID = 3, T_OP = 4, T_EOF = 5;

    private static class Token {
        int kind; String text; BigDecimal num;
        Token(int kind, String text) { this.kind = kind; this.text = text; }
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                String num = s.substring(i, j);
                try {
                    Token t = new Token(T_NUM, num);
                    t.num = new BigDecimal(num);
                    out.add(t);
                } catch (NumberFormatException e) {
                    throw new ExprException("非法数字：" + num);
                }
                i = j; continue;
            }
            if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                if (j < 0) throw new ExprException("字符串未闭合：" + s.substring(i));
                out.add(new Token(T_STR, s.substring(i + 1, j)));
                i = j + 1; continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Token(T_ID, s.substring(i, j)));
                i = j; continue;
            }
            // 双字符运算符优先
            String two = i + 1 < n ? s.substring(i, i + 2) : "";
            if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")
                    || two.equals("&&") || two.equals("||")) {
                out.add(new Token(T_OP, two)); i += 2; continue;
            }
            if ("+-*/%()<>!".indexOf(c) >= 0) {
                out.add(new Token(T_OP, String.valueOf(c))); i++; continue;
            }
            throw new ExprException("表达式含非法字符 '" + c + "'（仅支持数字、变量、+-*/%、括号与比较运算）");
        }
        out.add(new Token(T_EOF, ""));
        return out;
    }

    // ---------------- 递归下降 ----------------

    private static class Parser {
        private final List<Token> tokens;
        private final Map<String, Object> vars;
        private final boolean lenient;
        private int pos = 0;

        Parser(String expr, Map<String, Object> vars) {
            this(expr, vars, false);
        }

        Parser(String expr, Map<String, Object> vars, boolean lenient) {
            this.tokens = tokenize(expr);
            this.vars = vars;
            this.lenient = lenient;
        }

        Object parse() {
            Object v = parseOr();
            if (peek().kind != T_EOF) throw new ExprException("表达式语法错误，意外符号：" + peek().text);
            return v;
        }

        private Object parseOr() {
            Object v = parseAnd();
            while (matchOp("||")) {
                // 短路：左真则右操作数只求语法不求值（变量缺失/除零都不应触发）
                if (toBool(v)) { skipAnd(); v = Boolean.TRUE; }
                else v = toBool(parseAnd());
            }
            return v;
        }

        private Object parseAnd() {
            Object v = parseEq();
            while (matchOp("&&")) {
                if (!toBool(v)) { skipEq(); v = Boolean.FALSE; }
                else v = toBool(parseEq());
            }
            return v;
        }

        // ---- 短路跳过：按运算优先级消费右操作数的 token，但不做变量解析与运算 ----
        private void skipOr()  { skipAnd(); while (matchOp("||")) skipAnd(); }
        private void skipAnd() { skipEq();  while (matchOp("&&")) skipEq(); }
        private void skipEq()  { skipRel(); while (matchOp("==") || matchOp("!=")) skipRel(); }
        private void skipRel() { skipAdd();
            while (matchOp(">=") || matchOp("<=") || matchOp(">") || matchOp("<")) skipAdd(); }
        private void skipAdd() { skipMul(); while (matchOp("+") || matchOp("-")) skipMul(); }
        private void skipMul() { skipUnary(); while (matchOp("*") || matchOp("/") || matchOp("%")) skipUnary(); }
        private void skipUnary() {
            if (matchOp("-") || matchOp("!")) { skipUnary(); return; }
            Token t = next();
            if (t.kind == T_OP && "(".equals(t.text)) { skipOr(); if (!matchOp(")")) throw new ExprException("表达式缺少右括号"); return; }
            if (t.kind == T_ID) {
                if (peek().kind == T_OP && "(".equals(peek().text))
                    throw new ExprException("表达式不支持函数调用：" + t.text + "()");
                return;
            }
            if (t.kind != T_NUM && t.kind != T_STR)
                throw new ExprException("表达式语法错误，意外符号：" + t.text);
        }

        private Object parseEq() {
            Object v = parseRel();
            while (true) {
                if (matchOp("==")) v = compare(v, parseRel()) == 0;
                else if (matchOp("!=")) v = compare(v, parseRel()) != 0;
                else return v;
            }
        }

        private Object parseRel() {
            Object v = parseAdd();
            while (true) {
                if (matchOp(">=")) v = compare(v, parseAdd()) >= 0;
                else if (matchOp("<=")) v = compare(v, parseAdd()) <= 0;
                else if (matchOp(">")) v = compare(v, parseAdd()) > 0;
                else if (matchOp("<")) v = compare(v, parseAdd()) < 0;
                else return v;
            }
        }

        private Object parseAdd() {
            Object v = parseMul();
            while (true) {
                if (matchOp("+")) v = toBd(v).add(toBd(parseMul()));
                else if (matchOp("-")) v = toBd(v).subtract(toBd(parseMul()));
                else return v;
            }
        }

        private Object parseMul() {
            Object v = parseUnary();
            while (true) {
                if (matchOp("*")) v = toBd(v).multiply(toBd(parseUnary()));
                else if (matchOp("/")) {
                    BigDecimal d = toBd(parseUnary());
                    if (d.compareTo(BigDecimal.ZERO) == 0) throw new ExprException("表达式除数为零");
                    v = toBd(v).divide(d, 10, RoundingMode.HALF_UP);
                }
                else if (matchOp("%")) {
                    BigDecimal d = toBd(parseUnary());
                    if (d.compareTo(BigDecimal.ZERO) == 0) throw new ExprException("表达式除数为零");
                    v = toBd(v).remainder(d);
                }
                else return v;
            }
        }

        private Object parseUnary() {
            if (matchOp("-")) return toBd(parseUnary()).negate();
            if (matchOp("!")) return !toBool(parseUnary());
            return parsePrimary();
        }

        private Object parsePrimary() {
            Token t = peek();
            if (t.kind == T_NUM) { next(); return t.num; }
            if (t.kind == T_STR) { next(); return t.text; }
            if (t.kind == T_OP && "(".equals(t.text)) {
                next();
                Object v = parseOr();
                if (!matchOp(")")) throw new ExprException("表达式缺少右括号");
                return v;
            }
            if (t.kind == T_ID) {
                next();
                // 函数调用一律禁止（防注入：getClass()/open()/__import__ 等无路可走）
                if (peek().kind == T_OP && "(".equals(peek().text))
                    throw new ExprException("表达式不支持函数调用：" + t.text + "()");
                switch (t.text) {
                    case "true": return Boolean.TRUE;
                    case "false": return Boolean.FALSE;
                    case "null": return null;
                    default:
                        if (!vars.containsKey(t.text)) {
                            // 宽松模式（业务模板条件）：缺失变量视为空串，用于"字段为空"判断
                            if (lenient) return "";
                            throw new ExprException("表达式引用了不存在的变量：" + t.text);
                        }
                        return vars.get(t.text);
                }
            }
            throw new ExprException("表达式语法错误，意外符号：" + t.text);
        }

        private Token peek() { return tokens.get(pos); }
        private Token next() { return tokens.get(pos++); }
        private boolean matchOp(String op) {
            Token t = peek();
            if (t.kind == T_OP && t.text.equals(op)) { pos++; return true; }
            return false;
        }
    }

    /** 比较：数字按数值；字符串/空按文本；null 与 null 或空串视为相等；跨类型按字符串比较。 */
    private static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        boolean aEmpty = a == null || a.toString().isEmpty();
        boolean bEmpty = b == null || b.toString().isEmpty();
        if (aEmpty && bEmpty) return 0;
        if (a instanceof Number || a instanceof BigDecimal) {
            if (b instanceof Number || b instanceof BigDecimal) return toBd(a).compareTo(toBd(b));
        }
        if (b == null) return a.toString().isEmpty() ? 0 : 1;
        if (a == null) return b.toString().isEmpty() ? 0 : -1;
        return a.toString().compareTo(b.toString());
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return false;
        if (v instanceof BigDecimal) return ((BigDecimal) v).compareTo(BigDecimal.ZERO) != 0;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return !v.toString().isEmpty();
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        String s = v.toString().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ExprException("值不是数字，无法参与算术运算：" + s);
        }
    }
}

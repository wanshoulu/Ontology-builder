package com.ontologybuilder.ontology.service.reasoning;

import org.apache.jena.graph.Node;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Jena 自定义 builtin：逻辑非，对应 SWRL swrlb:not。
 *
 * 用法：swrlNot(?x)
 * 当 ?x 满足以下任一条件时返回 true：
 * - ?x 未绑定（null / variable）
 * - ?x 是布尔字面量 false
 * - ?x 是字符串 "false" 或数字 0
 */
public class SwrlNot extends BaseBuiltin {

    @Override
    public String getName() {
        return "swrlNot";
    }

    @Override
    public int getArgLength() {
        return 1;
    }

    @Override
    public boolean bodyCall(Node[] args, int length, RuleContext context) {
        checkArgs(length, context);
        Node n0 = getArg(0, args, context);

        // 变量未绑定 → true
        if (n0 == null || n0.isVariable()) return true;

        if (n0.isLiteral()) {
            String lex = n0.getLiteralLexicalForm();
            Object val = n0.getLiteralValue();

            // 布尔 false
            if (val instanceof Boolean) return !((Boolean) val);

            // 整数 0
            if (val instanceof Number) {
                double d = ((Number) val).doubleValue();
                return d == 0.0;
            }

            // 字符串 "false" 或 "0"
            return "false".equalsIgnoreCase(lex) || "0".equals(lex);
        }

        // URI 等非字面量资源 → false（有值即非"空"）
        return false;
    }
}

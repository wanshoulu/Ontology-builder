package com.ontologybuilder.ontology.service.reasoning;

import org.apache.jena.graph.Node;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Jena 自定义 builtin：字符串包含检查，对应 SWRL swrlb:contains。
 *
 * 用法：swrlStrContains(?str, "substr")
 * 检查 ?str 的词法形式是否包含 "substr"，忽略大小写。
 */
public class SwrlStrContains extends BaseBuiltin {

    @Override
    public String getName() {
        return "swrlStrContains";
    }

    @Override
    public int getArgLength() {
        return 2;
    }

    @Override
    public boolean bodyCall(Node[] args, int length, RuleContext context) {
        checkArgs(length, context);
        Node n0 = getArg(0, args, context);
        Node n1 = getArg(1, args, context);

        if (n0 == null || n1 == null) return false;

        String str = nodeToLexical(n0);
        String substr = nodeToLexical(n1);

        return str != null && substr != null && str.contains(substr);
    }

    private String nodeToLexical(Node node) {
        if (node.isLiteral()) return node.getLiteralLexicalForm();
        if (node.isURI()) return node.getURI();
        return node.toString();
    }
}

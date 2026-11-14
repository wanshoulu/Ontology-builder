package com.ontologybuilder.ontology.service.reasoning;

import org.apache.jena.graph.Node;
import org.apache.jena.reasoner.rulesys.BuiltinException;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Jena 自定义 builtin：大小写不敏感字符串比较
 * 对应 SWRL 的 swrlb:stringEqualIgnoreCase
 *
 * 用法：stringEqualIgnoreCase(?var, "literal")
 * 当 ?var 的字符串值与 "literal" 在忽略大小写时相等，返回 true
 */
public class StringEqualIgnoreCase extends BaseBuiltin {

    @Override
    public String getName() {
        return "stringEqualIgnoreCase";
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

        if (n0 == null || n1 == null) {
            return false;
        }

        String s0 = nodeToString(n0);
        String s1 = nodeToString(n1);

        return s0.equalsIgnoreCase(s1);
    }

    private String nodeToString(Node node) {
        if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        }
        if (node.isURI()) {
            return node.getURI();
        }
        return node.toString();
    }

    public void checkArgs(int length, RuleContext context) {
        if (length < 2) {
            throw new BuiltinException(this, context, "stringEqualIgnoreCase 需要 2 个参数，实际: " + length);
        }
    }
}

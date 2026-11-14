package com.ontologybuilder.ontology.service.reasoning;

import org.apache.jena.graph.Node;
import org.apache.jena.reasoner.rulesys.BindingEnvironment;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.Util;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Jena 自定义 builtin：绝对值计算，对应 SWRL swrlb:abs。
 *
 * 用法：swrlAbs(?input, ?result)
 * 计算 ?input 的绝对值（依据词法形式解析为 double），结果写入 ?result。
 */
public class SwrlAbs extends BaseBuiltin {

    @Override
    public String getName() {
        return "swrlAbs";
    }

    @Override
    public int getArgLength() {
        return 2;
    }

    @Override
    public boolean bodyCall(Node[] args, int length, RuleContext context) {
        checkArgs(length, context);
        BindingEnvironment env = context.getEnv();
        Node n0 = getArg(0, args, context);
        if (n0 == null || !n0.isLiteral()) return false;

        double val;
        try {
            val = Double.parseDouble(n0.getLiteralLexicalForm());
        } catch (NumberFormatException e) {
            return false;
        }

        Node result = getArg(1, args, context);
        if (result == null || !result.isVariable()) return false;

        double absVal = Math.abs(val);
        if (absVal == Math.floor(absVal) && !Double.isInfinite(absVal) && absVal <= Integer.MAX_VALUE) {
            return env.bind(result, Util.makeIntNode((int) absVal));
        }
        return env.bind(result, Util.makeDoubleNode(absVal));
    }
}

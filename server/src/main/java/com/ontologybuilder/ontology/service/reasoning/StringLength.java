package com.ontologybuilder.ontology.service.reasoning;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.reasoner.rulesys.BuiltinException;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Jena 自定义 builtin：计算字符串长度，对应 SWRL 的 swrlb:stringLength。
 *
 * 用法：stringLength(?len, ?str)
 * 当 ?str 绑定到字面量/URI 时，计算其词法形式的字符数并绑定到 ?len。
 */
public class StringLength extends BaseBuiltin {

    @Override
    public String getName() {
        return "stringLength";
    }

    @Override
    public int getArgLength() {
        return 2;
    }

    @Override
    public boolean bodyCall(Node[] args, int length, RuleContext context) {
        checkArgs(length, context);
        Node input = getArg(1, args, context);
        if (input == null) {
            return false;
        }

        String s;
        if (input.isLiteral()) {
            s = input.getLiteralLexicalForm();
        } else if (input.isURI()) {
            s = input.getURI();
        } else {
            s = input.toString();
        }

        int len = s.length();
        Node lenNode = NodeFactory.createLiteralByValue((long) len, null);
        return context.getEnv().bind(args[0], lenNode);
    }

    @Override
    public void checkArgs(int length, RuleContext context) {
        if (length < 2) {
            throw new BuiltinException(this, context,
                    "stringLength 需要 2 个参数（输出变量, 输入），实际: " + length);
        }
    }
}

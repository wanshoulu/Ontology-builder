package com.ontologybuilder.ontology.service.reasoning;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.reasoner.rulesys.BuiltinException;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.Util;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;

/**
 * Jena 自定义 builtin：可变参数 sum，替换 Jena 内置仅支持 3 参数的限制。
 * 用法：sum(?result, ?a, ?b, ... , ?n)
 * 第一个参数为输出变量，后续参数为输入，全部输入绑定后求和并绑定到输出。
 */
public class MultiArgSum extends BaseBuiltin {

    @Override
    public String getName() {
        return "sum";
    }

    @Override
    public int getArgLength() {
        return 0; // 可变参数
    }

    @Override
    public boolean bodyCall(Node[] args, int length, RuleContext context) {
        checkArgs(length, context);

        // 检查所有输入参数是否已绑定
        double total = 0;
        for (int i = 1; i < length; i++) {
            Node input = getArg(i, args, context);
            if (input == null || !input.isLiteral()) {
                return false;
            }
            try {
                total += Double.parseDouble(input.getLiteralLexicalForm());
            } catch (NumberFormatException e) {
                throw new BuiltinException(this, context,
                        "sum 参数 " + (i + 1) + " 不是有效数字: " + input.getLiteralLexicalForm());
            }
        }

        // 绑定结果到第一个参数（输出变量）
        Node resultNode;
        if (total == Math.floor(total) && !Double.isInfinite(total)) {
            resultNode = NodeFactory.createLiteralByValue((long) total, null);
        } else {
            resultNode = NodeFactory.createLiteralByValue(total, null);
        }
        return context.getEnv().bind(args[0], resultNode);
    }

    @Override
    public void checkArgs(int length, RuleContext context) {
        if (length < 3) {
            throw new BuiltinException(this, context,
                    "sum 至少需要 3 个参数 (1 个输出 + 2 个输入)，实际: " + length);
        }
    }
}

package com.ontologybuilder.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTL/Turtle 语法修复工具
 * 修复AI生成的TTL文件中的常见语法错误，特别是SWRL规则和中文IRI问题
 */
public class TtlSyntaxFixer {

    private static final Logger log = LoggerFactory.getLogger(TtlSyntaxFixer.class);

    /**
     * 修复TTL内容中的所有已知语法问题
     * 包括：SWRL AtomList声明、rdf:first/rest混合语法、swrl:arguments格式、中文IRI、SWRL变量声明
     */
    public static String fixAll(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }
        String result = ttlContent;
        boolean modified = false;

        // 修复1：移除集合括号内的 "a swrl:AtomList ;" 声明
        String before1 = result;
        result = result.replaceAll("\\(\\s*a\\s+swrl:AtomList\\s*;", "(");
        result = result.replaceAll("\\(\\s*rdf:type\\s+swrl:AtomList\\s*;", "(");
        if (!result.equals(before1)) modified = true;

        // 修复1.5：移除RDF集合括号内的逗号（LLM常生成Python风格 ( :a, :b, :c )）
        //   在标准Turtle中，集合内应以空格分隔，逗号是非法token
        String before15 = result;
        result = removeCommasInRdfCollections(result);
        if (!result.equals(before15)) modified = true;

        // 修复1.6：拆分逗号分隔的主语列表为独立三元组
        //   LLM常生成 "s1, s2, s3 a Type ." 格式，
        //   但Turtle中逗号只能分隔宾语（出现在谓词之后），不能分隔主语
        String before16 = result;
        result = fixCommaSeparatedSubjects(result);
        if (!result.equals(before16)) modified = true;

        // 修复2：修复 [ rdf:type rdf:nil ] → rdf:nil
        String before2 = result;
        result = result.replaceAll("\\[\\s*rdf:type\\s+rdf:nil\\s*\\]", "rdf:nil");
        result = result.replaceAll("swrl:rest\\s+\\[\\s*rdf:type\\s+rdf:nil\\s*\\]", "swrl:rest rdf:nil");
        result = result.replaceAll("rdf:rest\\s+\\[\\s*rdf:type\\s+rdf:nil\\s*\\]", "rdf:rest rdf:nil");
        if (!result.equals(before2)) modified = true;

        // 修复2.5：修复Protégé风格的SWRL atom简写（LLM常生成的格式）
        //   [ClassAtom :X var:y] → [ a swrl:ClassAtom ; swrl:classPredicate :X ; swrl:argument1 var:y ]
        //   [IndividualPropertyAtom :P var:x var:y] → [ a swrl:IndividualPropertyAtom ; ... ]
        //   [DatavaluedPropertyAtom :P var:x val] → [ a swrl:DatavaluedPropertyAtom ; ... ]
        //   [BuiltinAtom swrlb:func (args)] → [ a swrl:BuiltinAtom ; swrl:builtin swrlb:func ; swrl:arguments (args) ]
        String before25 = result;
        result = fixSwrlAtomShorthand(result);
        if (!result.equals(before25)) modified = true;

        // 修复3：处理 ( rdf:first [ a swrl:ClassAtom ; ... ] ; rdf:rest (...) ) 格式
        String before3 = result;
        result = fixSwrlCollectionSyntax(result);
        if (!result.equals(before3)) modified = true;

        // 修复4：修复 swrl:arguments 的非法语法
        String before4 = result;
        result = fixSwrlArgumentsSyntax(result);
        if (!result.equals(before4)) modified = true;

        // 修复4.5：内联 SWRL builtin 参数的包装值
        // LLM 有时将 builtin 常量参数生成为 :Arg_X_bodyY_Z a rdf:Statement ; rdf:value "..." ，
        // 在 swrl:arguments 中引用 IRI 而非内联字面量，导致推理引擎无法获取数值
        String before45 = result;
        result = inlineSwrlArgValues(result);
        if (!result.equals(before45)) modified = true;

        // 修复5：修复中文IRI本地名 — Protégé/OWL API对中文IRI支持不佳
        String before5 = result;
        result = fixChineseIriLocalNames(result);
        if (!result.equals(before5)) modified = true;

        // 修复6：为SWRL规则中使用的变量添加声明
        String before6 = result;
        result = addSwrlVariableDeclarations(result);
        if (!result.equals(before6)) modified = true;

        // 修复7：修复常见XSD类型拼写错误（LLM常犯的错误）
        String before7 = result;
        result = fixXsdTypeTypos(result);
        if (!result.equals(before7)) modified = true;

        // 修复8：修复Python风格的布尔字面量 (True/False → true/false)
        String before8 = result;
        result = fixBooleanLiterals(result);
        if (!result.equals(before8)) modified = true;

        // 修复9：修复字符串字面量中未转义的引号
        String before9 = result;
        result = fixUnescapedQuotesInLiterals(result);
        if (!result.equals(before9)) modified = true;

        // 修复10：修复孤立的谓词-宾语行（缺少主语），LLM有时会生成：
        //     rdfs:label "..." ;
        //     owl:versionInfo "..." ;
        // : a owl:Ontology .
        // 应当修复为 : a owl:Ontology ; rdfs:label "..." ; owl:versionInfo "..." .
        String before10 = result;
        result = fixOrphanedPredicateLines(result);
        if (!result.equals(before10)) modified = true;

        // 修复11：修复 LLM 输出的 <:XXX> 角括号包裹前缀名格式
        // LLM 常将 :Name 错误地写成 <:Name>，这在 Turtle 中会被解析为相对 URI
        // 例如 <:Customer> → :Customer
        String before11 = result;
        result = fixMalformedAngleBracketPrefixedNames(result);
        if (!result.equals(before11)) modified = true;

        // 修复12：修复被角括号包裹的 OWL class expression
        // LLM 有时生成 <owl:unionOf(:Class1 :Class2 ...)> 这种错误格式，
        // 将整个 owl:unionOf 表达式错误地包裹在尖括号内使其成为一个 IRI 引用。
        // 正确语法应为空白节点 [ owl:unionOf (:Class1 :Class2 ...) ]
        String before12 = result;
        result = fixAngleBracketWrappedClassExpressions(result);
        if (!result.equals(before12)) modified = true;

        // 修复13：压缩连续过多的空行
        // LLM 有时在输出中产生大段空白（10行以上的连续空行），
        // 影响可读性和解析性能，此处将3个以上的连续空行压缩为1个空行
        String before13 = result;
        result = collapseExcessiveBlankLines(result);
        if (!result.equals(before13)) modified = true;

        // 修复14：移除裸 "Ontology ." 语句
        // LLM 有时在 prefix 声明之后生成独立的 "Ontology ." 行，
        // 这不是合法的 Turtle 语法（Turtle 中没有 Ontology 关键字）。
        // 正确的本体声明应为 ": a owl:Ontology ." 或 "<> a owl:Ontology ."。
        // 该裸语句无实际语义价值，直接移除。
        String before14 = result;
        result = removeBareOntologyDeclaration(result);
        if (!result.equals(before14)) modified = true;

        // 修复15：补充缺失的常用前缀声明
        // LLM 有时生成 prop:xxx 形式但没有声明 @prefix prop:，
        // 导致 Jena 解析报 "Undefined prefix: prop" 错误而整体失败，
        // 回退到文本解析器后又因 bugs 导致 LinkType.sourceType/targetType 丢失。
        String before17 = result;
        result = fixMissingCommonPrefixes(result);
        if (!result.equals(before17)) modified = true;

        // 修复16：截断末尾不完整语句（LLM 输出截断导致 Jena EOF 解析错误）
        // LLM 流式输出可能被截断，导致最后一个语句不完整（如未闭合的字符串、缺少 . 终止符），
        // Jena 解析时会报 "Unrecognized (expected an RDF Term): [EOF]" 并导致整个 TTL 加载失败。
        // 此修复检测并移除末尾不完整的内容，使 TTL 至少可被 Jena 解析。
        String before18 = result;
        result = truncateIncompleteFinalStatement(result);
        if (!result.equals(before18)) modified = true;

        // 修复17：修复悬空的语句续符（dangling ; 和 dangling ; predicate）
        // LLM 有时生成不完整的 SWRL 规则头部（如 :Rule a swrl:Imp ; rdfs:comment），
        // 其中 rdfs:comment 是悬空谓词（有谓词无宾语），紧接着开始实例数据语句。
        // 这导致 Jena 解析报 "Triples not terminated by DOT"，因为前一个语句缺少 . 终止符。
        // 此修复检测并移除悬空的 ; 或 ; predicate，在语句末尾补上 . 终止符。
        String before19 = result;
        result = fixDanglingContinuations(result);
        if (!result.equals(before19)) modified = true;

        // 修复18：修正 xsd:dateTime 的空格分隔格式为 ISO 8601 T 分隔
        // 数据库 datetime 字段常输出 "2026-04-15 10:00:00" 格式，
        // 但 xsd:dateTime 要求 ISO 8601: "2026-04-15T10:00:00"
        // Jena 在 SWRL 规则执行时会严格校验类型，必须提前修正
        String before20 = result;
        result = fixDateTimeSpaceSeparator(result);
        if (!result.equals(before20)) modified = true;

        if (modified) {
            log.info("TTL语法修复: 已修复SWRL语法、中文IRI、变量声明、XSD类型、布尔字面量、孤立谓词、dateTime格式等问题");
        }

        return result;
    }

    /**
     * 修复常见的XSD类型拼写错误。
     * LLM常将 xsd:boolean 写成 xsd:bool, xsd:dateTime 写成 xsd:datetime 等。
     * 大写开头错误（如 xsd:Integer）在 Turtle 中会被解析为前缀名而非 XSD 类型，
     * 导致 Pellet 无法识别该类型而报不一致，因此必须修正。
     */
    private static String fixXsdTypeTypos(String ttlContent) {
        if (ttlContent == null) return ttlContent;
        String result = ttlContent;
        // 常见XSD类型错误映射：错误写法 → 正确写法
        // 注意：只修复真正无效的拼写，合法的 XSD 类型不应被替换
        Map<String, String> typoMap = new LinkedHashMap<>();
        // 缩写/拼写错误
        typoMap.put("xsd:bool", "xsd:boolean");
        typoMap.put("xsd:datetime", "xsd:dateTime");
        typoMap.put("xsd:DateTime", "xsd:dateTime");
        typoMap.put("xsd:str", "xsd:string");
        typoMap.put("xsd:text", "xsd:string");
        // 大写开头的大小写修正（XSD 类型均为小写开头，大写会导致 Pellet 无法识别）
        typoMap.put("xsd:Float", "xsd:float");
        typoMap.put("xsd:Long", "xsd:long");
        typoMap.put("xsd:Short", "xsd:short");
        typoMap.put("xsd:Byte", "xsd:byte");
        typoMap.put("xsd:Integer", "xsd:integer");
        typoMap.put("xsd:Double", "xsd:double");
        typoMap.put("xsd:Boolean", "xsd:boolean");
        typoMap.put("xsd:Decimal", "xsd:decimal");
        typoMap.put("xsd:String", "xsd:string");
        typoMap.put("xsd:Int", "xsd:int");
        typoMap.put("xsd:Date", "xsd:date");
        typoMap.put("xsd:Time", "xsd:time");
        typoMap.put("xsd:Duration", "xsd:duration");
        typoMap.put("xsd:AnyURI", "xsd:anyURI");
        typoMap.put("xsd:AnyUri", "xsd:anyURI");
        // LLM 幻觉出的非标准类型
        typoMap.put("xsd:number", "xsd:decimal");
        typoMap.put("xsd:Number", "xsd:decimal");
        typoMap.put("xsd:real", "xsd:double");
        typoMap.put("xsd:char", "xsd:string");
        typoMap.put("xsd:character", "xsd:string");
        typoMap.put("xsd:timestamp", "xsd:dateTime");
        typoMap.put("xsd:varchar", "xsd:string");
        typoMap.put("xsd:numeric", "xsd:decimal");
        for (Map.Entry<String, String> entry : typoMap.entrySet()) {
            String wrong = entry.getKey();
            String correct = entry.getValue();
            // 先检查是否存在（快速路径），再用词边界正则避免部分匹配
            // 例如 xsd:str 不应匹配 xsd:string 中的 xsd:str 片段
            if (result.contains(wrong)) {
                Pattern pattern = Pattern.compile(
                    Pattern.quote(wrong) + "(?![a-zA-Z0-9_\\-])");
                if (pattern.matcher(result).find()) {
                    result = pattern.matcher(result).replaceAll(correct);
                    log.debug("XSD类型修复: {} → {}", wrong, correct);
                }
            }
        }
        return result;
    }

    /**
     * 修复Python风格的布尔字面量
     * Turtle中布尔值应为 true/false（小写），LLM常输出 True/False
     *
     * 需要覆盖以下格式（LLM 可能以多种变体输出）：
     *   "True"^^xsd:boolean  /  'True'^^xsd:boolean     （双/单引号 + 前缀形式）
     *   "True"^^<...XMLSchema#boolean>                   （完整 IRI 形式）
     *   "true"^^xsd:boolean                              （小写但带类型 — 合法但会导致 SWRL equal() 类型不匹配）
     *   True / False                                      （裸 Python 关键字，被 Turtle 解析为 IRI）
     */
    private static String fixBooleanLiterals(String ttlContent) {
        if (ttlContent == null) return ttlContent;
        String result = ttlContent;

        // 所有模式统一输出裸布尔关键字 true / false（无引号，无类型标注）
        // 这样既能通过 Jena Riot 解析（合法 Turtle 布尔值），又能满足 OWL xsd:boolean 类型约束
        // 注意：不能输出 "true"（字符串），否则 OWL 属性声明为 xsd:boolean 时会导致本体不一致

        // 模式1：双引号 + xsd:boolean 前缀形式  "True"^^xsd:boolean → true
        result = result.replaceAll("\"True\"\\^\\^xsd:boolean", "true");
        result = result.replaceAll("\"False\"\\^\\^xsd:boolean", "false");

        // 模式2：单引号 + xsd:boolean 前缀形式  'True'^^xsd:boolean → true
        // Turtle 同样支持单引号字符串，LLM 可能输出这种格式
        result = result.replaceAll("'True'\\^\\^xsd:boolean", "true");
        result = result.replaceAll("'False'\\^\\^xsd:boolean", "false");

        // 模式3：双引号 + 完整 IRI 形式  "True"^^<http://.../XMLSchema#boolean> → true
        result = result.replaceAll(
                "\"True\"\\^\\^<http://www\\.w3\\.org/2001/XMLSchema#boolean>", "true");
        result = result.replaceAll(
                "\"False\"\\^\\^<http://www\\.w3\\.org/2001/XMLSchema#boolean>", "false");

        // 模式4：单引号 + 完整 IRI 形式  'True'^^<http://.../XMLSchema#boolean> → true
        result = result.replaceAll(
                "'True'\\^\\^<http://www\\.w3\\.org/2001/XMLSchema#boolean>", "true");
        result = result.replaceAll(
                "'False'\\^\\^<http://www\\.w3\\.org/2001/XMLSchema#boolean>", "false");

        // 模式5：小写但带类型的布尔值 → 裸布尔关键字
        //   "true"^^xsd:boolean → true （合法但统一为裸关键字，减少类型标注噪音）
        result = result.replaceAll("[\"']true[\"']\\^\\^xsd:boolean", "true");
        result = result.replaceAll("[\"']false[\"']\\^\\^xsd:boolean", "false");

        // 模式6：裸 Python 布尔关键字（在属性值位置）
        // 这种格式：property True ; 或 property False ;
        // 在Turtle中这会被解析为IRI，导致类型错误
        // 修复策略：将独立的 True/False 替换为 true/false（仅当它们作为属性值出现时）
        // 使用更精确的匹配：前面是空白，后面是空白/分号/句点
        result = java.util.regex.Pattern.compile("(?<=[\\s;])(True|False)(?=[\\s;.])")
                .matcher(result)
                .replaceAll(m -> m.group(1).equals("True") ? "true" : "false");
        return result;
    }

    /**
     * 修复字符串字面量中未转义的双引号
     * LLM有时会在双引号字符串内部使用双引号，导致解析失败
     */
    private static String fixUnescapedQuotesInLiterals(String ttlContent) {
        if (ttlContent == null) return ttlContent;
        // 检测双引号字符串内部包含未转义双引号的行
        // 模式：一个带引号的字符串，内部有未转义的双引号
        // 例如："value with "internal" quotes" → "value with \"internal\" quotes"
        StringBuilder result = new StringBuilder();
        String[] lines = ttlContent.split("\n", -1);
        for (String line : lines) {
            // 计数该行中双引号的数量，如果超出正常范围（>2且为偶数），
            // 尝试修复内部的未转义双引号
            int quoteCount = line.length() - line.replace("\"", "").length();
            if (quoteCount > 2 && quoteCount % 2 == 0) {
                // 可能包含多个带引号的字符串 -- 安全起见，跳过自动修复
                result.append(line).append("\n");
                continue;
            }
            if (quoteCount > 2) {
                // 奇数个引号：可能有未转义的内部引号
                // 尝试找到最外层引号对之间的内容并转义内部引号
                String fixed = fixQuotesInLine(line);
                result.append(fixed).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString().trim();
    }

    /**
     * 修复单行中未转义的双引号
     */
    private static String fixQuotesInLine(String line) {
        // 找到第一个和最后一个双引号之间的内容，转义内部的双引号
        int firstQuote = line.indexOf('"');
        int lastQuote = line.lastIndexOf('"');
        if (firstQuote >= 0 && lastQuote > firstQuote) {
            String prefix = line.substring(0, firstQuote + 1);
            String middle = line.substring(firstQuote + 1, lastQuote);
            String suffix = line.substring(lastQuote);
            // 转义内部未转义的双引号
            middle = middle.replaceAll("(?<!\\\\)\"", "\\\\\"");
            return prefix + middle + suffix;
        }
        return line;
    }

    /**
     * 修复孤立的谓词-宾语行（缺少主语的悬空行）
     * LLM 有时会生成缩进的属性行后紧跟 : a owl:Ontology . 的模式：
     * <pre>
     *     rdfs:label "标签" ;
     *     owl:versionInfo "1.0" ;
     * : a owl:Ontology .
     * </pre>
     * 修复为：: a owl:Ontology ; rdfs:label "标签" ; owl:versionInfo "1.0" .
     */
    private static String fixOrphanedPredicateLines(String ttlContent) {
        if (ttlContent == null || !ttlContent.contains(": a owl:Ontology")) {
            return ttlContent;
        }
        // 匹配：一行或多行缩进的属性行（以 ; 结尾），紧接着 : a owl:Ontology .
        Pattern orphanedBlock = Pattern.compile(
            "((?:^[ \\t]+(?:rdfs|owl|dc|dcterms|skos|dct):\\w+\\s+.*$\\s*)+)^[ \\t]*:\\s+a\\s+owl:Ontology\\s*\\.",
            Pattern.MULTILINE
        );
        Matcher m = orphanedBlock.matcher(ttlContent);
        if (!m.find()) {
            return ttlContent;
        }
        StringBuffer sb = new StringBuffer();
        do {
            String propLines = m.group(1).trim();
            // 提取每条属性行（去掉末尾的 ;）
            StringBuilder propsBlock = new StringBuilder();
            String[] lines = propLines.split("\\n");
            for (String line : lines) {
                String stripped = line.trim();
                if (stripped.endsWith(";")) {
                    stripped = stripped.substring(0, stripped.length() - 1).trim();
                }
                if (propsBlock.length() > 0) {
                    propsBlock.append(" ;\n    ");
                }
                propsBlock.append(stripped);
            }
            // 重构：主语在前，属性在后
            String replacement = ": a owl:Ontology ;\n    " + propsBlock.toString() + " .";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        } while (m.find());
        m.appendTail(sb);
        log.debug("孤立的谓词-宾语行已修复: 已将属性行合并到 : a owl:Ontology 语句中");
        return sb.toString();
    }

    /**
     * 修复错误的 &lt;:XXX&gt; 角括号包裹前缀名语法
     * 某些 TTL 序列化工具/LLM 会错误地输出 &lt;:ClassName&gt; 这种格式，
     * 这在 Turtle 中会被解析为相对 URI（而非预期的前缀名展开），导致类/
     * 属性/个体无法与使用 :ClassName 格式的数据关联。
     * 例如 &lt;:Customer&gt;、&lt;:ElectricityBill&gt; 修复为 :Customer、:ElectricityBill
     */
    private static String fixMalformedAngleBracketPrefixedNames(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }
        // 匹配 <:LocalName> 格式，其中 LocalName 为合法的 Turtle 本地名
        // （以字母或下划线开头，后跟字母数字、下划线、连字符）
        // 不匹配 <:http://...> 这类完整 IRI
        Pattern pattern = Pattern.compile(
            "<\\s*:([a-zA-Z_][\\w\\-]*)\\s*>");
        Matcher matcher = pattern.matcher(ttlContent);
        if (!matcher.find()) {
            return ttlContent;
        }
        StringBuffer sb = new StringBuffer();
        int count = 0;
        matcher.reset();
        while (matcher.find()) {
            matcher.appendReplacement(sb, ":" + matcher.group(1));
            count++;
        }
        matcher.appendTail(sb);
        log.info("角括号前缀名修复: 已将 {} 处 <:XXX> 替换为 :XXX", count);
        return sb.toString();
    }

    /**
     * 修复被角括号包裹的 OWL class expression（如 &lt;owl:unionOf(...)&gt;）
     * LLM 有时错误地将 owl:unionOf / owl:intersectionOf / owl:oneOf 表达式
     * 包裹在尖括号中，例如：
     *   rdfs:domain &lt;owl:unionOf(:Class1 :Class2)&gt;
     * 这在 Turtle 中会被解析为一个绝对 IRI，导致引用不到真实类。
     * 正确语法应为空白节点形式：
     *   rdfs:domain [ owl:unionOf (:Class1 :Class2) ]
     */
    private static String fixAngleBracketWrappedClassExpressions(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }
        // 匹配 <owl:unionOf(...)>、<owl:intersectionOf(...)>、<owl:oneOf(...)>
        // 括号内可以为任意数量的 :ClassName 用空格分隔
        Pattern pattern = Pattern.compile(
            "<\\s*(owl:(?:unionOf|intersectionOf|oneOf|complementOf))\\s*\\(([^)]*)\\)\\s*>");
        Matcher matcher = pattern.matcher(ttlContent);
        if (!matcher.find()) {
            return ttlContent;
        }
        StringBuffer sb = new StringBuffer();
        int count = 0;
        matcher.reset();
        while (matcher.find()) {
            String owlConstruct = matcher.group(1);      // e.g. owl:unionOf
            String innerList = matcher.group(2).trim();  // e.g. :Class1 :Class2
            // 将内部用空格分隔的类名规范化：去除多余空白
            String normalized = innerList.replaceAll("\\s+", " ");
            matcher.appendReplacement(sb,
                "[ " + owlConstruct + " (" + normalized + ") ]");
            count++;
        }
        matcher.appendTail(sb);
        if (count > 0) {
            log.info("OWL class expression 角括号修复: 已将 {} 处 <owl:XXX(...)> 替换为空白节点形式", count);
        }
        return sb.toString();
    }

    /**
     * 压缩连续过多的空行
     * 2个以上的连续空行压缩为1个空行，提高 TTL 文件的可读性和解析效率
     */
    private static String collapseExcessiveBlankLines(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }
        // 先统一换行符：Windows CRLF → LF，避免跨平台换行符导致正则匹配遗漏
        String normalized = ttlContent.replace("\r\n", "\n").replace("\r", "\n");
        // 匹配2个以上连续空行（仅包含空白字符的行），替换为单个空行
        String result = normalized.replaceAll(
            "([ \\t]*\\n){2,}", "\n\n");
        if (result.length() < ttlContent.length()) {
            int removed = ttlContent.length() - result.length();
            log.info("空白行压缩: 已移除约 {} 个冗余空行字符", removed);
        }
        return result;
    }

    /**
     * 移除裸 "Ontology ." 声明
     * LLM 有时在 prefix 声明之后生成独立的 "Ontology ." 行，
     * 这不是合法的 Turtle 语法（Turtle 中 Ontology 不是关键字）。
     * 该裸语句无实际语义价值，直接移除。
     * 可能的形式：
     *   Ontology .
     *   Ontology.
     *   ontology .
     */
    private static String removeBareOntologyDeclaration(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }
        // 匹配独立一行的 "Ontology ." 或 "Ontology."（大小写不敏感，前后可有空白）
        Pattern pattern = Pattern.compile(
            "^[ \\t]*(?:Ontology|ontology)[ \\t]*\\.[ \\t]*$",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
        Matcher matcher = pattern.matcher(ttlContent);
        if (matcher.find()) {
            String result = matcher.replaceAll("");
            // 清理可能产生的连续空行
            result = result.replaceAll("\\n{3,}", "\n\n");
            log.info("裸Ontology声明修复: 已移除无效的 'Ontology .' 语句");
            return result;
        }
        return ttlContent;
    }

    /**
     * 修复中文IRI本地名
     * Protégé/OWL API对中文IRI支持不佳，会导致个体无法识别
     * 策略：将包含中文的本地名替换为英文标识符（优先使用rdfs:label的拼音/翻译映射，否则使用编号），
     *       在rdfs:label中保留原始中文名
     */
    private static String fixChineseIriLocalNames(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        // 仅在含 SWRL 规则时执行中文 IRI 修复（SWRL 是 Protégé/OWL API 对中文 IRI 的主要兼容性场景）
        // 不含 SWRL 的标准 TTL 中，中文 IRI 是合法且应该被保留的
        if (!ttlContent.contains("swrl:Imp") && !ttlContent.contains("swrl:Atom")) {
            return ttlContent;
        }

        // 检测是否存在中文IRI本地名：匹配 :后面紧跟中文字符的模式
        Pattern chineseIriPattern = Pattern.compile(
            ":(\\p{script=Han}[\\w\\u4e00-\\u9fff]*)");
        Matcher matcher = chineseIriPattern.matcher(ttlContent);

        // 收集所有中文本地名及其类型
        LinkedHashMap<String, String> chineseToEnglish = new LinkedHashMap<>();
        int individualCounter = 0;
        int classCounter = 0;
        int propertyCounter = 0;
        int otherCounter = 0;

        while (matcher.find()) {
            String chineseName = matcher.group(1);
            if (!chineseToEnglish.containsKey(chineseName)) {
                String fullName = ":" + chineseName;
                if (ttlContent.contains(fullName + " a owl:Class") ||
                    ttlContent.contains(fullName + " rdf:type owl:Class")) {
                    classCounter++;
                    chineseToEnglish.put(chineseName, "Class_" + classCounter);
                } else if (ttlContent.contains(fullName + " a owl:DatatypeProperty") ||
                           ttlContent.contains(fullName + " a owl:ObjectProperty") ||
                           ttlContent.contains(fullName + " rdf:type owl:DatatypeProperty") ||
                           ttlContent.contains(fullName + " rdf:type owl:ObjectProperty")) {
                    propertyCounter++;
                    chineseToEnglish.put(chineseName, "Property_" + propertyCounter);
                } else if (ttlContent.contains(fullName + " a owl:NamedIndividual") ||
                           ttlContent.contains(fullName + " rdf:type owl:NamedIndividual")) {
                    individualCounter++;
                    // 为个体生成更有语义的英文名：尝试从rdfs:label提取翻译映射
                    String englishName = generateEnglishIriName(chineseName, "Individual", individualCounter, ttlContent);
                    chineseToEnglish.put(chineseName, englishName);
                } else {
                    otherCounter++;
                    chineseToEnglish.put(chineseName, "Resource_" + otherCounter);
                }
            }
        }

        if (chineseToEnglish.isEmpty()) {
            return ttlContent;
        }

        // 执行替换：将所有 :中文名 替换为 :英文名
        String result = ttlContent;
        for (Map.Entry<String, String> entry : chineseToEnglish.entrySet()) {
            String chineseName = entry.getKey();
            String englishName = entry.getValue();
            // 替换所有 :中文名 的出现（作为IRI本地名）
            // 匹配 :中文名 后面跟着空白、分号、逗号、句点、右括号、右方括号
            result = result.replaceAll(":" + Pattern.quote(chineseName) + "(?=[\\s;,.\\]\\)])",
                                       ":" + englishName);
            // 也处理 :中文名 出现在行首或独立位置
            result = result.replaceAll(":" + Pattern.quote(chineseName) + "$",
                                       ":" + englishName);
        }

        log.info("中文IRI修复: 已将{}个中文本地名替换为英文名: {}", chineseToEnglish.size(), chineseToEnglish);

        return result;
    }

    /**
     * 为中文IRI生成英文替代名
     * 使用常见的中文-英文映射表，如果没有匹配则使用编号
     */
    private static String generateEnglishIriName(String chineseName, String prefix, int counter, String ttlContent) {
        // 常见进出口贸易术语中英文映射
        Map<String, String> translationMap = new HashMap<>();
        // 结算产品
        translationMap.put("汇入汇款", "InwardRemittance");
        translationMap.put("汇出汇款", "OutwardRemittance");
        translationMap.put("工银e秒达", "ICBCeQuickPay");
        translationMap.put("出口跟单托收", "ExportDocumentaryCollection");
        translationMap.put("出口信用证", "ExportLetterOfCredit");
        translationMap.put("进口跟单代收", "ImportDocumentaryCollection");
        translationMap.put("进口信用证", "ImportLetterOfCredit");
        // 融资产品
        translationMap.put("打包贷款", "PackingLoan");
        translationMap.put("出口订单融资", "ExportOrderFinance");
        translationMap.put("出口发票融资", "ExportInvoiceFinance");
        translationMap.put("出口押汇与贴现", "ExportBillPurchaseAndDiscount");
        translationMap.put("出口押汇", "ExportBillPurchase");
        translationMap.put("国际福费廷", "InternationalForfaiting");
        translationMap.put("出口保理融资", "ExportFactoringFinance");
        translationMap.put("短期出口信用保险项下贸易融资", "ShortTermExportCreditInsuranceFinance");
        translationMap.put("进口预付货款融资", "ImportPrepaymentFinance");
        translationMap.put("进口TT融资", "ImportTTFinance");
        translationMap.put("进口押汇", "ImportBillPurchase");
        translationMap.put("服务贸易对外应付款融资", "ServiceTradePayableFinance");
        // 担保产品
        translationMap.put("对外担保", "ExternalGuarantee");
        translationMap.put("对外担保_进口", "ExternalGuaranteeImport");
        // 类
        translationMap.put("贸易产品", "TradeProduct");
        translationMap.put("结算类产品", "SettlementProduct");
        translationMap.put("融资类产品", "FinancingProduct");
        translationMap.put("担保类产品", "GuaranteeProduct");
        translationMap.put("客户", "Customer");
        translationMap.put("出口商", "Exporter");
        translationMap.put("进口商", "Importer");
        translationMap.put("贸易阶段", "TradeStage");
        translationMap.put("结算方式", "SettlementMethod");

        String englishName = translationMap.get(chineseName);
        if (englishName != null) {
            return englishName;
        }

        // 没有映射则使用编号
        return prefix + "_" + counter;
    }

    /**
     * 为SWRL规则中使用的变量添加 swrl:Variable 声明
     */
    private static String addSwrlVariableDeclarations(String ttlContent) {
        if (ttlContent == null || !ttlContent.contains("swrl:Imp")) {
            return ttlContent;
        }

        // 预扫描：收集所有已知实体名（NamedIndividual / Class / Property），它们不是变量
        Set<String> knownEntities = new HashSet<>();
        // 匹配 :Name a owl:Class 或 :Name rdf:type owl:Class 等形式
        Pattern entityPattern = Pattern.compile(
            ":(\\w+)\\s+(?:a|rdf:type)\\s+(?:owl:NamedIndividual|owl:Class|owl:ObjectProperty|owl:DatatypeProperty|owl:AnnotationProperty)\\b");
        Matcher entityMatcher = entityPattern.matcher(ttlContent);
        while (entityMatcher.find()) {
            knownEntities.add(entityMatcher.group(1));
        }
        // 匹配作为主语出现在常见RDF语句中的实体（:Name rdfs:label / rdfs:domain 等）
        // 这些实体可能在 SWRL 中被引用为参数（即 OWL 个体），不应被误判为变量
        Pattern subjectPattern = Pattern.compile(
            "^:(\\w+)\\s+(?:rdfs:label|rdfs:comment|rdfs:domain|rdfs:range|owl:onProperty|rdfs:subClassOf|rdfs:subPropertyOf|owl:equivalentClass|owl:disjointWith)",
            Pattern.MULTILINE);
        Matcher subjectMatcher = subjectPattern.matcher(ttlContent);
        while (subjectMatcher.find()) {
            knownEntities.add(subjectMatcher.group(1));
        }
        // swrl:classPredicate / swrl:propertyPredicate 引用的也是实体，不是变量
        Pattern predPattern = Pattern.compile("swrl:(?:classPredicate|propertyPredicate)\\s+:(\\w+)");
        Matcher predMatcher = predPattern.matcher(ttlContent);
        while (predMatcher.find()) {
            knownEntities.add(predMatcher.group(1));
        }

        // ★ 兼容两种变量前缀格式：:varName（默认命名空间）和 var:varName（var: 前缀）
        Set<String> variables = new LinkedHashSet<>();
        String varPrefix = null;  // 检测 TTL 中实际使用的变量前缀，null 表示尚未确定
        Pattern varPattern = Pattern.compile("swrl:argument[12]\\s+((?::|var:)(\\w+))");
        Matcher varMatcher = varPattern.matcher(ttlContent);
        while (varMatcher.find()) {
            String fullMatch = varMatcher.group(1); // e.g., ":x" or "var:x"
            String varName = varMatcher.group(2);   // e.g., "x"
            // 检测实际使用的变量前缀
            if (varPrefix == null && fullMatch.startsWith("var:")) {
                varPrefix = "var:";
            }
            if (knownEntities.contains(varName)) {
                continue;
            }
            if (!varName.equals("ClassAtom") && !varName.equals("DatavaluedPropertyAtom") &&
                !varName.equals("ObjectPropertyAtom") && !varName.equals("BuiltinAtom") &&
                !varName.equals("IndividualPropertyAtom") && !varName.equals("Imp")) {
                variables.add(varName);
            }
        }
        // 默认使用 : 前缀（兼容没有显式变量前缀声明的旧 TTL）
        if (varPrefix == null) varPrefix = ":";

        if (variables.isEmpty()) {
            return ttlContent;
        }

        // ★ 兼容两种前缀检测已有声明
        Set<String> existingDeclarations = new HashSet<>();
        Pattern existingPattern = Pattern.compile(
            "(?::|var:)(\\w+)\\s+a\\s+swrl:Variable");
        Matcher existingMatcher = existingPattern.matcher(ttlContent);
        while (existingMatcher.find()) {
            existingDeclarations.add(existingMatcher.group(1));
        }

        List<String> newDeclarations = new ArrayList<>();
        for (String varName : variables) {
            if (!existingDeclarations.contains(varName)) {
                newDeclarations.add(varPrefix + varName + " a swrl:Variable .");
            }
        }

        if (newDeclarations.isEmpty()) {
            return ttlContent;
        }

        String declarationBlock = "\n# ===== SWRL 变量声明 =====\n" +
            String.join("\n", newDeclarations) + "\n\n";

        int rulePos = ttlContent.indexOf(":Rule1 a swrl:Imp");
        if (rulePos == -1) {
            Pattern ruleStartPattern = Pattern.compile(":[\\w:]+\\s+a\\s+swrl:Imp");
            Matcher ruleStartMatcher = ruleStartPattern.matcher(ttlContent);
            if (ruleStartMatcher.find()) {
                rulePos = ruleStartMatcher.start();
            }
        }

        if (rulePos == -1) {
            int swrlCommentPos = ttlContent.indexOf("SWRL");
            if (swrlCommentPos > 0) {
                int lineStart = ttlContent.lastIndexOf('\n', swrlCommentPos) + 1;
                return ttlContent.substring(0, lineStart) + declarationBlock + ttlContent.substring(lineStart);
            }
            return ttlContent + "\n" + declarationBlock;
        }

        return ttlContent.substring(0, rulePos) + declarationBlock + ttlContent.substring(rulePos);
    }

    /**
     * 修复 Protégé 风格的 SWRL atom 简写为标准的 Turtle 空白节点属性列表格式。
     * LLM 经常生成 Protégé SWRLTab 的简写格式，其中在 [...] 内直接使用
     * ClassAtom / IndividualPropertyAtom / DatavaluedPropertyAtom / BuiltinAtom
     * 作为类型标记，但这不是合法的 Turtle 语法（裸词无前缀，且缺少 rdf:type 标记）。
     *
     * 转换规则：
     *   [ClassAtom :C var:v]              → [ a swrl:ClassAtom ; swrl:classPredicate :C ; swrl:argument1 var:v ]
     *   [IndividualPropertyAtom :P var:x var:y] → [ a swrl:IndividualPropertyAtom ; swrl:propertyPredicate :P ; swrl:argument1 var:x ; swrl:argument2 var:y ]
     *   [DatavaluedPropertyAtom :P var:x val]   → [ a swrl:DatavaluedPropertyAtom ; swrl:propertyPredicate :P ; swrl:argument1 var:x ; swrl:argument2 val ]
     *   [BuiltinAtom swrlb:func (args)]   → [ a swrl:BuiltinAtom ; swrl:builtin swrlb:func ; swrl:arguments (args) ]
     */
    private static String fixSwrlAtomShorthand(String ttlContent) {
        if (ttlContent == null || !ttlContent.contains("swrl:Imp")) {
            return ttlContent;
        }
        String result = ttlContent;
        boolean modified = false;

        // 用于匹配一个独立的 token：非空白/非方括号/非圆括号的连续字符，或双引号字符串（不含内嵌引号）
        // 注意：BuiltinAtom 的参数列表 (...) 由独立模式处理，不在此 token 范围内
        String TOKEN = "(?:[^\\s\\[\\]\\(\\)]+|\"[^\"]*\")";

        // --- 1) ClassAtom: [ClassAtom :ClassName var:var] ---
        // group(1)=classPredicate, group(2)=argument1
        Pattern classAtom = Pattern.compile(
            "\\[\\s*ClassAtom\\s+(" + TOKEN + ")\\s+(" + TOKEN + ")\\s*\\]");
        if (classAtom.matcher(result).find()) {
            result = classAtom.matcher(result).replaceAll(
                "[ a swrl:ClassAtom ; swrl:classPredicate $1 ; swrl:argument1 $2 ]");
            modified = true;
        }

        // --- 2) IndividualPropertyAtom: [IndividualPropertyAtom :prop var:subj var:obj] ---
        // group(1)=propertyPredicate, group(2)=argument1, group(3)=argument2
        Pattern indPropAtom = Pattern.compile(
            "\\[\\s*IndividualPropertyAtom\\s+(" + TOKEN + ")\\s+(" + TOKEN + ")\\s+(" + TOKEN + ")\\s*\\]");
        if (indPropAtom.matcher(result).find()) {
            result = indPropAtom.matcher(result).replaceAll(
                "[ a swrl:IndividualPropertyAtom ; swrl:propertyPredicate $1 ; swrl:argument1 $2 ; swrl:argument2 $3 ]");
            modified = true;
        }

        // --- 3) DatavaluedPropertyAtom: [DatavaluedPropertyAtom :prop var:x value] ---
        // group(1)=propertyPredicate, group(2)=argument1, group(3)=argument2 (可以是变量或字面量)
        Pattern dataPropAtom = Pattern.compile(
            "\\[\\s*DatavaluedPropertyAtom\\s+(" + TOKEN + ")\\s+(" + TOKEN + ")\\s+(" + TOKEN + ")\\s*\\]");
        if (dataPropAtom.matcher(result).find()) {
            result = dataPropAtom.matcher(result).replaceAll(
                "[ a swrl:DatavaluedPropertyAtom ; swrl:propertyPredicate $1 ; swrl:argument1 $2 ; swrl:argument2 $3 ]");
            modified = true;
        }

        // --- 4) BuiltinAtom: [BuiltinAtom swrlb:func (args)] ---
        // group(1)=builtin (如 swrlb:equal), group(2)=arguments (含括号，如 "(var:f \"1\")")
        Pattern builtinAtom = Pattern.compile(
            "\\[\\s*BuiltinAtom\\s+(" + TOKEN + ")\\s+(\\([^)]*\\))\\s*\\]");
        if (builtinAtom.matcher(result).find()) {
            result = builtinAtom.matcher(result).replaceAll(
                "[ a swrl:BuiltinAtom ; swrl:builtin $1 ; swrl:arguments $2 ]");
            modified = true;
        }

        if (modified) {
            log.info("SWRL atom简写修复: 已将 ClassAtom/IndividualPropertyAtom/DatavaluedPropertyAtom/BuiltinAtom 转为标准SWRL格式");
        }

        return result;
    }

    /**
     * 修复 swrl:arguments 的非法语法
     */
    private static String fixSwrlArgumentsSyntax(String ttlContent) {
        if (ttlContent == null || !ttlContent.contains("swrl:arguments")) {
            return ttlContent;
        }

        Pattern pattern = Pattern.compile("swrl:arguments\\s+(?!\\()(.+?)(?=\\s*[\\];])");
        Matcher matcher = pattern.matcher(ttlContent);
        StringBuffer sb = new StringBuffer();
        boolean modified = false;

        while (matcher.find()) {
            String args = matcher.group(1).trim();
            if (args.startsWith("(")) {
                continue;
            }
            String replacement = "swrl:arguments ( " + args + " )";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            modified = true;
        }
        matcher.appendTail(sb);

        if (modified) {
            log.info("SWRL swrl:arguments语法修复: 已将非集合格式的arguments转为RDF集合格式");
        }

        return sb.toString();
    }

    /**
     * 修复SWRL规则中 swrl:body/swrl:head 后面的 ( rdf:first [...] ; rdf:rest (...) ) 混合格式
     * 将其转换为纯集合语法 ( [...] [...] )
     */
    private static String fixSwrlCollectionSyntax(String ttlContent) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int len = ttlContent.length();

        while (i < len) {
            int bodyIdx = ttlContent.indexOf("swrl:body", i);
            int headIdx = ttlContent.indexOf("swrl:head", i);

            int nextSwrl = -1;
            if (bodyIdx >= 0 && (headIdx < 0 || bodyIdx <= headIdx)) {
                nextSwrl = bodyIdx;
            } else if (headIdx >= 0) {
                nextSwrl = headIdx;
            }

            if (nextSwrl < 0) {
                result.append(ttlContent.substring(i));
                break;
            }

            result.append(ttlContent.substring(i, nextSwrl));

            int parenStart = ttlContent.indexOf('(', nextSwrl + 9);
            if (parenStart < 0) {
                result.append(ttlContent.substring(nextSwrl));
                break;
            }

            int parenEnd = findMatchingParen(ttlContent, parenStart);
            if (parenEnd < 0) {
                result.append(ttlContent.substring(nextSwrl));
                break;
            }

            String content = ttlContent.substring(parenStart, parenEnd + 1);

            if (content.contains("rdf:first") || content.contains("rdf:rest")) {
                List<String> atoms = extractAtomsFromFirstRest(content);
                if (!atoms.isEmpty()) {
                    result.append(ttlContent, nextSwrl, parenStart);
                    result.append("( ");
                    result.append(String.join(" ", atoms));
                    result.append(" )");
                    i = parenEnd + 1;
                    continue;
                }
            }

            result.append(ttlContent, nextSwrl, parenEnd + 1);
            i = parenEnd + 1;
        }

        return result.toString();
    }

    private static int findMatchingParen(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> extractAtomsFromFirstRest(String content) {
        List<String> atoms = new ArrayList<>();
        // 从 rdf:first [ ... ] 中提取原子
        Pattern firstPattern = Pattern.compile("rdf:first\\s+(\\[.*?\\])");
        Matcher firstMatcher = firstPattern.matcher(content);
        while (firstMatcher.find()) {
            atoms.add(firstMatcher.group(1));
        }
        // 如果没有 rdf:first，尝试直接提取 [ a swrl:... ] 原子
        if (atoms.isEmpty()) {
            Pattern atomPattern = Pattern.compile("(\\[\\s*a\\s+swrl:\\w+Atom[^\\]]*\\])");
            Matcher atomMatcher = atomPattern.matcher(content);
            while (atomMatcher.find()) {
                atoms.add(atomMatcher.group(1));
            }
        }
        return atoms;
    }

    /**
     * 移除RDF集合括号内的逗号
     * LLM常生成Python风格的 ( :a, :b, :c ) 而不是标准的 ( :a :b :c )
     * 在标准Turtle中，集合内应以空格分隔，逗号是非法token
     */
    private static String removeCommasInRdfCollections(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        StringBuilder result = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        int start = 0;

        for (int i = 0; i < ttlContent.length(); i++) {
            char ch = ttlContent.charAt(i);
            if (inString) {
                if (ch == '\\' && i + 1 < ttlContent.length()) {
                    i++; // skip escaped char
                    continue;
                }
                if (ch == stringChar) {
                    inString = false;
                }
            } else {
                if (ch == '"' || ch == '\'') {
                    inString = true;
                    stringChar = ch;
                } else if (ch == '(') {
                    if (depth == 0) {
                        result.append(ttlContent, start, i);
                        start = i;
                    }
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        String inner = ttlContent.substring(start, i + 1);
                        inner = inner.replace(',', ' ');
                        result.append(inner);
                        start = i + 1;
                    }
                }
            }
        }
        if (start < ttlContent.length()) {
            result.append(ttlContent.substring(start));
        }
        return result.toString();
    }

    /**
     * 拆分逗号分隔的主语列表为独立三元组
     * LLM常生成 "s1, s2, s3 a Type ." 这种Python列表风格，
     * 但在Turtle中逗号只能分隔宾语（出现在谓词之后），不能分隔主语。
     * 修复为:
     *   s1 a Type .
     *   s2 a Type .
     *   s3 a Type .
     */
    private static String fixCommaSeparatedSubjects(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        // 匹配: subject1, subject2, ..., subjectN predicate object .
        // 谓词: 'a' (rdf:type) 或 QName prefix:localname
        Pattern pattern = Pattern.compile(
            "^([\\w:][\\w:\\-]*)\\s*,\\s*(.+)\\s+((?:a|[\\w]+:[\\w][\\w:\\-]*)\\s+.+\\.)$",
            Pattern.MULTILINE
        );
        Matcher matcher = pattern.matcher(ttlContent);
        if (!matcher.find()) {
            return ttlContent;
        }

        StringBuffer sb = new StringBuffer();
        matcher.reset();
        while (matcher.find()) {
            String first = matcher.group(1);
            String rest = matcher.group(2);
            String predObj = matcher.group(3);

            // 从 rest 中提取所有主语（按逗号分割）
            String[] restSubjects = rest.split("\\s*,\\s*");
            StringBuilder replacement = new StringBuilder();
            replacement.append(first).append(" ").append(predObj);
            for (String subj : restSubjects) {
                subj = subj.trim();
                if (!subj.isEmpty()) {
                    replacement.append("\n").append(subj).append(" ").append(predObj);
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);

        log.info("逗号分隔主语列表修复: 已将逗号分隔的主语拆分为独立三元组");
        return sb.toString();
    }

    /**
     * 修复缺失的常用前缀声明。
     * LLM 生成 TTL 时常使用 prop: 前缀来区分属性与类，
     * 但可能遗漏 @prefix prop: 声明，导致 Jena 解析报 "Undefined prefix" 错误。
     * 该错误会触发文本回退解析器并引入更多数据完整性 bug。
     */
    private static String fixMissingCommonPrefixes(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        // 查找基础命名空间（默认 : 前缀对应的 IRI）
        String baseNs = null;
        Pattern basePrefixPat = Pattern.compile("@prefix\\s*:\\s*<([^>]+)>");
        Matcher baseMatcher = basePrefixPat.matcher(ttlContent);
        if (baseMatcher.find()) {
            baseNs = baseMatcher.group(1);
        }

        // 如果找不到基础命名空间，无法补充其他前缀
        if (baseNs == null) {
            return ttlContent;
        }

        // 需检查的常用前缀及其对应的命名空间
        // "prop" 与 "" 通常指向同一命名空间（LLM 用于区分属性与类）
        List<String[]> prefixChecks = new ArrayList<>();
        prefixChecks.add(new String[]{"prop", baseNs});

        StringBuilder result = new StringBuilder();
        boolean modified = false;

        for (String[] check : prefixChecks) {
            String prefixName = check[0];
            String ns = check[1];

            // 检查前缀是否已声明
            Pattern declaredPat = Pattern.compile("@prefix\\s+" + Pattern.quote(prefixName) + "\\s*:");
            if (declaredPat.matcher(ttlContent).find()) {
                continue; // 已声明，跳过
            }

            // 检查前缀是否在 TTL 中被使用
            // 匹配空格、(、[、;、, 等 Turtle 语法中可能出现在前缀前的字符
            Pattern usagePat = Pattern.compile("(?:^|[\\s\\(\\[\\;,])" + Pattern.quote(prefixName) + ":");
            if (!usagePat.matcher(ttlContent).find()) {
                continue; // 未使用，无需添加
            }

            // 在最后一个 @prefix 行之后插入缺失的前缀声明
            int insertPos = -1;
            // 注意：前缀名匹配必须同时处理 @prefix name: <iri> 和 @prefix : <iri> 两种格式。
            // (?:\\w+)? 使前缀名可选（兼容默认前缀），\\s*:\\s* 处理冒号两边的空格。
            Matcher prefixLineMatcher = Pattern.compile("@prefix\\s+(?:\\w+)?\\s*:\\s*<[^>]+>\\s*\\.", Pattern.MULTILINE).matcher(ttlContent);
            while (prefixLineMatcher.find()) {
                insertPos = prefixLineMatcher.end();
            }

            if (insertPos > 0) {
                // 跳过末尾的空白字符到下一行开头
                while (insertPos < ttlContent.length() && (ttlContent.charAt(insertPos) == ' ' || ttlContent.charAt(insertPos) == '\t')) {
                    insertPos++;
                }
                // 跳到该行末尾
                int lineEnd = ttlContent.indexOf('\n', insertPos);
                if (lineEnd < 0) lineEnd = ttlContent.length();

                String prefixDecl = "@prefix " + prefixName + ": <" + ns + "> .\n";
                result.append(ttlContent, 0, insertPos > 0 ? (lineEnd < ttlContent.length() ? lineEnd + 1 : lineEnd) : 0);
                result.append(prefixDecl);
                result.append(ttlContent, insertPos > 0 ? (lineEnd < ttlContent.length() ? lineEnd + 1 : lineEnd) : 0, ttlContent.length());
                log.info("TTL语法修复: 补充缺失的 @prefix {}: 声明", prefixName);
                modified = true;
                // 更新 ttlContent 以供后续检查使用
                ttlContent = result.toString();
            }
        }

        if (!modified) {
            return ttlContent;
        }
        return result.toString();
    }

    /**
     * 内联 SWRL builtin 参数的包装值。
     * LLM 有时将 builtin 常量参数生成为：
     *   :Arg_1_body2_1 a rdf:Statement ; rdf:value "90"^^xsd:string .
     * 并在 swrl:arguments 中引用 IRI：
     *   swrl:arguments (:Var_score :Arg_1_body2_1)
     * 这会导致推理引擎无法解析 Arg IRI 获取实际数值。
     * 修复为直接内联字面量，并将纯数字值转为 bare integer（OxiRS 兼容）：
     *   swrl:arguments (:Var_score 90)
     * 同时移除冗余的 :Arg_* 声明三元组。
     */
    private static String inlineSwrlArgValues(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }
        // 仅在包含 Arg 引用时执行
        if (!ttlContent.contains(":Arg_")) {
            return ttlContent;
        }

        // Step 1: 收集所有 :Arg_<name> → literal replacement 的映射
        // group(1)=Arg_xxx, group(2)="VAL", group(3)=^^xsd:TYPE or null
        Map<String, String> argMap = new LinkedHashMap<>();
        Pattern argDefPattern = Pattern.compile(
            ":(Arg_\\w+)\\s+a\\s+rdf:Statement\\s*;\\s*rdf:value\\s+\"([^\"]*)\"(\\^\\^xsd:\\w+)?\\s*\\.",
            Pattern.DOTALL
        );
        Matcher m = argDefPattern.matcher(ttlContent);
        while (m.find()) {
            String rawVal = m.group(2);
            // 纯数字值 → "VAL"^^xsd:integer（兼容 OxiRS 类型检查）
            // 非数字值 → 保留原样的 quoted string
            String replacement;
            if (rawVal.matches("\\d+")) {
                replacement = "\"" + rawVal + "\"^^xsd:integer";
            } else {
                replacement = "\"" + rawVal + "\"";
            }
            argMap.put(":" + m.group(1), replacement);
        }

        if (argMap.isEmpty()) {
            return ttlContent;
        }

        String result = ttlContent;

        // Step 2: 先移除 :Arg_* 声明三元组（必须在Step 3替换引用之前执行，
        //         否则 replace 会将定义行中的 :Arg_* 也替换为字面量，导致正则无法匹配）
        for (String argName : argMap.keySet()) {
            String argPrefix = argName.substring(1); // 去掉 :
            result = result.replaceAll(
                ":" + Pattern.quote(argPrefix) + "\\s+a\\s+rdf:Statement\\s*;\\s*rdf:value\\s+\"[^\"]*\"(?:\\^\\^xsd:\\w+)?\\s*\\.\\s*",
                ""
            );
        }

        // Step 3: 全局替换 :Arg_* 引用为内联字面量
        // :Arg_X_bodyY_Z 标识符足够独特，不会误匹配其他内容
        // 定义三元组已在 Step 2 中移除，此处只替换 swrl:arguments 等处的引用
        int replaced = 0;
        for (Map.Entry<String, String> entry : argMap.entrySet()) {
            String argIri = entry.getKey();
            String replacement = entry.getValue();
            if (result.contains(argIri)) {
                result = result.replace(argIri, replacement);
                replaced++;
            }
        }

        if (replaced > 0) {
            log.info("SWRL builtin参数内联: 已将 {} 个 Arg 包装引用替换为内联字面量", replaced);
        }

        return result;
    }

    /**
     * 截断末尾不完整的语句，避免 Jena 解析时报 EOF 错误。
     *
     * LLM 流式输出可能被截断，导致 TTL 文件末尾存在不完整的语句。
     * 此方法只处理有明确证据的截断场景，避免误删合法内容：
     *
     * 1. 未闭合的字符串字面量 (奇数个引号) → 关闭字符串并添加终止符
     * 2. 末尾括号/方括号不成对（未闭合的 RDF 集合或空白节点）→ 补全并截断
     *
     * 注意：不再做"查找最后一个 . 截断"的激进策略，
     * 因为合法的 SWRL 规则行可能因 Protégé 简写展开等原因被误判。
     */
    private static String truncateIncompleteFinalStatement(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        String[] lines = ttlContent.split("\n", -1);
        if (lines.length == 0) return ttlContent;

        // 修复1：检测未闭合的字符串字面量（奇数个引号）
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            int quoteCount = countUnescapedQuotes(line);
            if (quoteCount % 2 != 0) {
                lines[i] = line + "\" .";
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j <= i; j++) {
                    if (j > 0) sb.append("\n");
                    sb.append(lines[j]);
                }
                log.info("TTL截断修复: 关闭未闭合的字符串字面量（第{}行），并截断后续 {} 行",
                        i + 1, lines.length - i - 1);
                return sb.toString();
            }
        }

        // 修复2：检测末尾括号/方括号不成对（未闭合的 RDF 集合或空白节点）
        // 从最后一行向后扫描，累计括号差值，如果末尾净余额 > 0 则截断
        int parenBalance = 0;
        int bracketBalance = 0;
        boolean inString = false;
        for (int i = 0; i < ttlContent.length(); i++) {
            char c = ttlContent.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '(') parenBalance++;
            if (c == ')') parenBalance--;
            if (c == '[') bracketBalance++;
            if (c == ']') bracketBalance--;
        }
        // 括号净余额 > 0 说明有未闭合的结构，找到最后一个完整语句并截断
        if (parenBalance > 0 || bracketBalance > 0) {
            // 从末尾向前找到最后一个完整语句（. 结尾、引号成对）
            int lastComplete = findLastCompleteStatement(lines);
            if (lastComplete >= 0 && lastComplete < lines.length - 1) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j <= lastComplete; j++) {
                    if (j > 0) sb.append("\n");
                    sb.append(lines[j]);
                }
                log.info("TTL截断修复: 检测到未闭合的括号/方括号 (paren={}, bracket={}), 截断末尾 {} 行",
                        parenBalance, bracketBalance, lines.length - lastComplete - 1);
                return sb.toString();
            }
        }

        // 修复3：检测末尾不完整语句（裸谓词无宾语，如 "rdfs:comment" 后无值）
        // 如果最后一行以 Turtle 谓词结尾但没有对象值，说明 LLM 输出在此处被截断
        String lastNonEmptyLine = null;
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty()) {
                lastNonEmptyLine = trimmed;
                break;
            }
        }
        if (lastNonEmptyLine != null) {
            // 检测截断特征：行末是裸谓词（以 ; 结尾但后面无内容，或以特定谓词结尾无值）
            boolean looksTruncated = false;
            // 特征1：以 "rdfs:comment" 结尾（无注释值）
            if (lastNonEmptyLine.endsWith("rdfs:comment") || lastNonEmptyLine.endsWith("rdfs:label")) {
                looksTruncated = true;
            }
            // 特征2：以 "swrl:body" 或 "swrl:head" 结尾（无原子列表）
            if (lastNonEmptyLine.endsWith("swrl:body") || lastNonEmptyLine.endsWith("swrl:head")) {
                looksTruncated = true;
            }
            // 特征3：以 ; 结尾但下一个谓词-宾语对被截断
            // （"a swrl:Imp ;" 后缺属性，"rdfs:comment" 已包含在特征1）
            if (lastNonEmptyLine.endsWith(";") && countUnescapedQuotes(lastNonEmptyLine) % 2 == 0) {
                // 以 ; 结尾意味着还有后续属性，但文件到此结束 → 截断
                looksTruncated = true;
            }
            if (looksTruncated) {
                int lastComplete = findLastCompleteStatement(lines);
                if (lastComplete >= 0 && lastComplete < lines.length - 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j <= lastComplete; j++) {
                        if (j > 0) sb.append("\n");
                        sb.append(lines[j]);
                    }
                    log.info("TTL截断修复: 检测到末尾不完整语句(行末='{}...'), 截断末尾 {} 行",
                            lastNonEmptyLine.length() > 50 ? lastNonEmptyLine.substring(0, 50) : lastNonEmptyLine,
                            lines.length - lastComplete - 1);
                    return sb.toString();
                }
            }
        }

        return ttlContent;
    }

    private static int findLastCompleteStatement(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith(".") && countUnescapedQuotes(trimmed) % 2 == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 统计字符串中未转义的双引号数量。
     * 反斜杠转义的下一个字符不计入。
     */
    private static int countUnescapedQuotes(String s) {
        int count = 0;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                count++;
            }
        }
        return count;
    }

    /**
     * 修复悬空的语句续符（dangling ; 和 dangling ; predicate）。
     *
     * LLM 有时生成不完整的语句，例如：
     * <pre>
     *   :Rule_1 a swrl:Imp ; rdfs:comment
     *   &lt;http://...&gt; &lt;http://...&gt; "value" .
     * </pre>
     * 其中第一行末尾的 "; rdfs:comment" 是一个悬空谓词（缺少宾语），
     * 紧接着的下一行以完整 IRI 开头，开始了新的实例数据语句。
     * 这种格式导致 Jena 解析报 "Triples not terminated by DOT"。
     *
     * 检测并修复两种模式：
     * 1. 行末尾为 ";"（无后续续符内容）→ 将 ";" 替换为 " ."
     * 2. 行末尾为 "; prefix:name"（有谓词无宾语）→ 移除 "; prefix:name" 并添加 " ."
     *
     * 仅在下一行明确为新的主语（以角括号 IRI、前缀名等开头）时才执行修复，
     * 避免误伤合法的多行续行。
     */
    private static String fixDanglingContinuations(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        // 先统一换行符
        String normalized = ttlContent.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        boolean modified = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 跳过空白行、注释、前缀/基础声明
            if (trimmed.isEmpty() || trimmed.startsWith("#")
                    || trimmed.startsWith("@prefix") || trimmed.startsWith("@base")) {
                continue;
            }

            // ===== 模式1：行末尾为 ";"（悬空分号，无后续续符内容）=====
            if (trimmed.endsWith(";")) {
                int nextIdx = findNextMeaningfulLine(lines, i + 1);
                if (nextIdx >= 0 && isClearlyNewSubject(lines[nextIdx].trim())) {
                    // 将末尾的 ";" 替换为 " ."
                    lines[i] = line.replaceFirst(";\\s*$", " .");
                    modified = true;
                    log.debug("悬空分号修复 (行{}): 将末尾 ';' 替换为 '.'", i + 1);
                }
                continue;
            }

            // ===== 模式2：行末尾为 "; prefix:name"（悬空谓词，有谓词无宾语）=====
            // 匹配：; 后跟空白，再跟一个有效的 QName（prefix:localname），行尾无其他内容
            java.util.regex.Pattern danglingPred = java.util.regex.Pattern.compile(
                    ";\\s+([a-zA-Z_]\\w*:[a-zA-Z_]\\w*)\\s*$");
            java.util.regex.Matcher m = danglingPred.matcher(trimmed);
            if (m.find()) {
                int nextIdx = findNextMeaningfulLine(lines, i + 1);
                if (nextIdx >= 0 && isClearlyNewSubject(lines[nextIdx].trim())) {
                    String danglingPart = m.group(); // e.g., "; rdfs:comment"
                    int posInLine = line.lastIndexOf(danglingPart);
                    if (posInLine < 0) {
                        // 悬空部分可能被前导空白包围，尝试用修剪后的匹配位置
                        posInLine = line.indexOf(danglingPart.trim());
                    }
                    if (posInLine >= 0) {
                        lines[i] = line.substring(0, posInLine) + " .";
                        modified = true;
                        log.debug("悬空谓词修复 (行{}): 移除 '{}'，添加 '.'", i + 1, danglingPart.trim());
                    }
                }
            }
        }

        if (modified) {
            log.info("TTL语法修复: 已修复悬空的语句续符（dangling ; / ; predicate）");
            return String.join("\n", lines);
        }
        return ttlContent;
    }

    /**
     * 从 start 行开始，查找下一个非空、非注释的有意义行。
     * @return 有意义行的索引，若找不到则返回 -1
     */
    private static int findNextMeaningfulLine(String[] lines, int start) {
        for (int i = start; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /**
     * 判断一行内容是否明确为一个新语句的主语开头。
     *
     * 以下情况视为明确的新主语：
     * - 以 &lt; 开头且包含至少 3 个空白分隔的 token（完整 IRI 主语+谓词+宾语，
     *   LLM 生成的实例数据），排除仅有一个 IRI 对象值的续行场景
     * - 以 _: 开头（具名空白节点作为主语）
     * - 以 ( 开头（RDF 集合作为主语，少见但合法）
     *
     * 以下情况不视为"明确的新主语"（太容易与谓词续行混淆）：
     * - 以 : 或 前缀名 开头：可能是新主语，也可能是 ; 后的续行谓词
     * - 以 [ 开头：可能是主语位置的匿名空白节点，也可能是宾语位置
     * - 以 &lt; 开头但只有 1-2 个 token：可能只是上一个谓词的 IRI 宾语
     */
    private static boolean isClearlyNewSubject(String trimmed) {
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        // <http://...> — 区分完整三元组与单 IRI 宾语续行
        if (first == '<') {
            // 去掉末尾的 "." 后按空白分割，3+ token 表示主语-谓词-宾语结构
            String body = trimmed.endsWith(".") && trimmed.length() > 1
                    ? trimmed.substring(0, trimmed.length() - 1).trim()
                    : trimmed;
            // 简单按空白分割（不深入处理引号字符串，对 LLM 输出足够了）
            String[] tokens = body.split("\\s+");
            return tokens.length >= 3;
        }
        // _:blankNode — 具名空白节点作为主语
        if (first == '_' && trimmed.length() > 1 && trimmed.charAt(1) == ':') {
            return true;
        }
        // ( — RDF 集合作为主语（少见但合法）
        if (first == '(') {
            return true;
        }
        return false;
    }

    /**
     * 修复 xsd:dateTime 字面量中的空格分隔符为 ISO 8601 标准的 T 分隔符。
     *
     * 数据库 datetime 字段常输出 "2026-04-15 10:00:00" 格式，
     * 但 xsd:dateTime 要求 ISO 8601: "2026-04-15T10:00:00"。
     * Jena 在解析和 SWRL 规则执行时会对带有类型标注的字面量进行严格校验，
     * 不符合词法约束的字面量会导致 DatatypeFormatException。
     *
     * 支持两种 xsd:dateTime 引用格式：
     *   1. 前缀格式: "2026-04-15 10:00:00"^^xsd:dateTime
     *   2. 完整 IRI:  "2026-04-15 10:00:00"^^&lt;http://www.w3.org/2001/XMLSchema#dateTime&gt;
     */
    private static String fixDateTimeSpaceSeparator(String ttlContent) {
        if (ttlContent == null || ttlContent.isEmpty()) {
            return ttlContent;
        }

        // 模式1: "YYYY-MM-DD HH:MM:SS"^^xsd:dateTime → "YYYY-MM-DDTHH:MM:SS"^^xsd:dateTime
        Pattern prefixPattern = Pattern.compile(
                "\"(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})\"\\^\\^xsd:dateTime");
        int count1 = countMatches(prefixPattern.matcher(ttlContent));
        String result = prefixPattern.matcher(ttlContent).replaceAll("\"$1T$2\"^^xsd:dateTime");

        // 模式2: "YYYY-MM-DD HH:MM:SS"^^<...XMLSchema#dateTime>
        Pattern iriPattern = Pattern.compile(
                "\"(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})\"\\^\\^<http://www\\.w3\\.org/2001/XMLSchema#dateTime>");
        int count2 = countMatches(iriPattern.matcher(result));
        result = iriPattern.matcher(result).replaceAll(
                "\"$1T$2\"^^<http://www.w3.org/2001/XMLSchema#dateTime>");

        int fixed = count1 + count2;
        if (fixed > 0) {
            log.info("dateTime空格分隔修复: 已修正 {} 处 xsd:dateTime 字面量 (空格→T)", fixed);
        }
        return result;
    }

    private static int countMatches(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }
}

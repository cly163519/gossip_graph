package com.laura;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("未提供参数，运行演示模式。");
            demoRun();
            return;
        }
        String mode = args[0]; // "graph" 或 "timeline"
        Map<String, String> cli = parseArgs(Arrays.copyOfRange(args, 1, args.length));

        switch (mode) {
            case "graph":
                runGraph(cli);
                break;
            case "timeline":
                runTimeline(cli);
                break;
            default:
                System.out.println("未知模式：" + mode);
                printHelp();
        }
    }

    // ====== 演示模式（不使用文本块，不用全角标点）======
    private static void demoRun() throws Exception {
        String demoText =
                "甄嬛爱上了果郡王. 雍正喜欢甄嬛. 华妃嫉妒甄嬛. 安陵容背叛了甄嬛. " +
                        "甄嬛和雍正在一起. 果郡王敌视雍正. 沈眉庄支持甄嬛.";

        System.out.println("【演示】从剧情文字抽取关系并导出 graph.dot ...");
        buildGraphFromTextAndExport(demoText, "graph.dot");
        System.out.println("已生成 graph.dot（Graphviz：dot -Tpng graph.dot -o graph.png）");

        System.out.println("\n【演示】处理时间线 demo_events（内置）...");
        List<String> lines = Arrays.asList(
                "1722-12-20 雍正登基",
                "1720-06-01 甄嬛入宫(虚构示例)",
                "1723-08-15 果郡王远行(虚构示例)",
                "1724-01-10 华妃失宠(虚构示例)"
        );
        List<TimelineEvent> events = parseEvents(lines);
        events.sort(Comparator.comparing(e -> e.date));
        printEvents(events);
    }

    // ====== 图模式 ======
    private static void runGraph(Map<String, String> cli) throws Exception {
        String text = cli.get("text");
        String file = cli.get("file");
        String out = cli.getOrDefault("out", "graph.dot");

        if (text == null && file == null) {
            System.out.println("graph 模式需要 --text=\"...\" 或 --file=路径");
            printHelp();
            return;
        }
        String content = (text != null)
                ? text
                : new String(Files.readAllBytes(Path.of(file)), StandardCharsets.UTF_8);

        buildGraphFromTextAndExport(content, out);
        System.out.println("已生成 " + out + "（Graphviz：dot -Tpng " + out + " -o graph.png）");
    }

    // ====== 时间线模式 ======
    private static void runTimeline(Map<String, String> cli) throws Exception {
        String file = cli.get("file");
        if (file == null) {
            System.out.println("timeline 模式需要 --file=事件文件（每行：YYYY-MM-DD 事件描述）");
            printHelp();
            return;
        }
        List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        List<TimelineEvent> events = parseEvents(lines);
        events.sort(Comparator.comparing(e -> e.date));
        printEvents(events);
    }

    // ====== 构建关系图并导出 DOT ======
    private static void buildGraphFromTextAndExport(String text, String outDotPath) throws IOException {
        List<Relation> relations = extractRelations(text);

        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<DefaultEdge, String> edgeLabel = new LinkedHashMap<>();

        for (Relation r : relations) {
            g.addVertex(r.from);
            g.addVertex(r.to);

            DefaultEdge e = g.getEdge(r.from, r.to);
            if (e == null) {
                e = g.addEdge(r.from, r.to);
                edgeLabel.put(e, r.type);
            } else {
                String cur = edgeLabel.getOrDefault(e, "");
                Set<String> tags = new LinkedHashSet<>();
                if (!cur.isEmpty()) tags.addAll(Arrays.asList(cur.split("\\|")));
                tags.add(r.type);
                edgeLabel.put(e, String.join("|", tags));
            }
        }

        DOTExporter<String, DefaultEdge> exporter = new DOTExporter<>(v -> v);
        exporter.setVertexAttributeProvider(v -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            m.put("label", DefaultAttribute.createAttribute(v));
            return m;
        });
        exporter.setEdgeAttributeProvider(e -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            String label = edgeLabel.getOrDefault(e, "");
            if (!label.isEmpty()) {
                m.put("label", DefaultAttribute.createAttribute(label));
            }
            return m;
        });

        try (Writer w = new BufferedWriter(new FileWriter(new File(outDotPath), StandardCharsets.UTF_8))) {
            exporter.exportGraph(g, w);
        }
    }

    // ====== 关系抽取（简易中文规则）======
    private static final String NAME = "(?<A>[\\p{IsHan}A-Za-z0-9_]{1,8})";
    private static final String NAME_B = "(?<B>[\\p{IsHan}A-Za-z0-9_]{1,8})";

    private static final List<Rule> RULES = Arrays.asList(
            new Rule(Pattern.compile(NAME + "\\s*(喜欢|爱上了?|爱了|暗恋)\\s*" + NAME_B), "love"),
            new Rule(Pattern.compile(NAME + "\\s*(和|与)\\s*" + NAME_B + "\\s*(在一起|成亲|结婚)"), "couple"),
            new Rule(Pattern.compile(NAME + "\\s*(嫉妒|吃醋|妒忌|敌视|仇恨)\\s*" + NAME_B), "rival"),
            new Rule(Pattern.compile(NAME + "\\s*(背叛|陷害|害)了?\\s*" + NAME_B), "betray"),
            new Rule(Pattern.compile(NAME + "\\s*(支持|帮助|维护|偏向)\\s*" + NAME_B), "support")
    );

    private static List<Relation> extractRelations(String text) {
        // 把中文标点统一成空格 + 英文句号，避免编码差异
        String normalized = text
                .replaceAll("[，、；;]", " ")
                .replaceAll("[。！？!?]", ". ");
        List<Relation> out = new ArrayList<>();
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(normalized);
            while (m.find()) {
                String a = m.group("A");
                String b = m.group("B");
                if (a != null && b != null && !a.equals(b)) {
                    out.add(new Relation(a.trim(), b.trim(), r.type));
                }
            }
        }
        return out;
    }

    // ====== 时间线 ======
    private static class TimelineEvent {
        LocalDate date;
        String desc;
        TimelineEvent(LocalDate d, String s) { this.date = d; this.desc = s; }
    }

    private static List<TimelineEvent> parseEvents(List<String> lines) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<TimelineEvent> list = new ArrayList<>();
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split("\\s+", 2);
            if (parts.length < 2) continue;
            try {
                LocalDate d = LocalDate.parse(parts[0], fmt);
                list.add(new TimelineEvent(d, parts[1]));
            } catch (Exception ignore) {}
        }
        return list;
    }

    private static void printEvents(List<TimelineEvent> events) {
        System.out.println("—— 时间线（按日期升序）——");
        for (TimelineEvent e : events) {
            System.out.println(e.date + "  " + e.desc);
        }
    }

    // ====== CLI ======
    private static Map<String, String> parseArgs(String[] arr) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : arr) {
            if (a.startsWith("--")) {
                int i = a.indexOf('=');
                if (i > 2) {
                    m.put(a.substring(2, i), trimQuotes(a.substring(i + 1)));
                } else {
                    m.put(a.substring(2), "true");
                }
            }
        }
        return m;
    }

    private static String trimQuotes(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static class Relation {
        String from, to, type;
        Relation(String f, String t, String ty) { from = f; to = t; type = ty; }
    }

    private static class Rule {
        Pattern pattern; String type;
        Rule(Pattern p, String t) { pattern = p; type = t; }
    }

    private static void printHelp() {
        System.out.println(
                "用法：\n" +
                        "  演示：   mvn -q exec:java\n" +
                        "  关系图： mvn -q exec:java -Dexec.args=\"graph --file=data/demo_text.txt --out=graph.dot\"\n" +
                        "  时间线： mvn -q exec:java -Dexec.args=\"timeline --file=data/demo_events.txt\"\n");
    }
}

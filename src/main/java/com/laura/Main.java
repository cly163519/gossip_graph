package com.laura;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GossipGraph: 从中/英文自然语言文本抽取人物关系，导出 DOT，并尽力调用 Graphviz 生成 PNG。
 * 也支持简单的时间线（YYYY-MM-DD 事件描述）。
 *
 * 用法：
 *   演示：   java com.laura.Main
 *   关系图： java com.laura.Main graph --file=data/my_text.txt --out=graph.dot --png=graph.png
 *            或：java com.laura.Main graph --text="A loves B. B betrayed C." --out=graph.dot
 *   时间线： java com.laura.Main timeline --file=data/my_events.txt
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            demoRun();
            return;
        }
        String mode = args[0].trim().toLowerCase(Locale.ROOT);
        Map<String, String> cli = parseArgs(Arrays.copyOfRange(args, 1, args.length));
        switch (mode) {
            case "graph":
                runGraph(cli);
                break;
            case "timeline":
                runTimeline(cli);
                break;
            default:
                System.out.println("Unknown mode: " + mode);
        }
    }

    // ======================= 演示 =======================
    private static void demoRun() throws Exception {
        String demoText =
                "ZhenHuan loves King GuoJun. " +
                        "YongZheng loves ZhenHuan. " +
                        "HuaFei is jealous of ZhenHuan. " +
                        "AnLingRong betrayed ZhenHuan. " +
                        "ZhenHuan is together with YongZheng. " +
                        "King GuoJun is hostile to YongZheng. " +
                        "ShenMeiZhuang supports ZhenHuan.";

        String dot = "graph.dot";
        String png = "graph.png";
        buildGraphFromTextAndExport(demoText, dot, png);
        System.out.println("Generated " + dot + (new File(png).exists() ? (" & " + png) : ""));
        List<TimelineEvent> demo = Arrays.asList(
                new TimelineEvent(LocalDate.of(1720, 6, 1),  "ZhenHuan enters the palace"),
                new TimelineEvent(LocalDate.of(1722, 12, 20),"YongZheng enthroned"),
                new TimelineEvent(LocalDate.of(1723, 8, 15),"GuoJun Prince travels"),
                new TimelineEvent(LocalDate.of(1724, 1, 10), "HuaFei falls from favor")
        );
        demo.sort(Comparator.comparing(e -> e.date));
        printEvents(demo);
    }

    // ======================= Graph 模式 =======================
    private static void runGraph(Map<String, String> cli) throws Exception {
        String text = cli.get("text");
        String file = cli.get("file");
        String outDot = cli.getOrDefault("out", "graph.dot");
        String outPng = cli.getOrDefault("png", "graph.png");
        if (text == null && file == null) {
            System.out.println("graph mode needs --text=\"...\" or --file=path");
            return;
        }
        String content = (text != null)
                ? text
                : Files.readString(Path.of(file), StandardCharsets.UTF_8);

        buildGraphFromTextAndExport(content, outDot, outPng);
        System.out.println("Generated " + outDot + (new File(outPng).exists() ? (" & " + outPng) : ""));
    }

    // ======================= Timeline 模式 =======================
    private static void runTimeline(Map<String, String> cli) throws Exception {
        String file = cli.get("file");
        if (file == null) {
            System.out.println("timeline mode needs --file=events.txt  (each line: YYYY-MM-DD <desc>)");
            return;
        }
        List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        List<TimelineEvent> events = parseEvents(lines);
        events.sort(Comparator.comparing(e -> e.date));
        printEvents(events);
    }

    // ======================= 核心：从文本抽取关系，导出 DOT/PNG =======================
    private static void buildGraphFromTextAndExport(String text, String outDotPath, String outPngPath) throws IOException, InterruptedException {
        List<Relation> relations = extractRelations(text);

        Graph<String, DefaultEdge> g = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<DefaultEdge, String> edgeLabel = new LinkedHashMap<>();
        Map<DefaultEdge, String> edgeType  = new LinkedHashMap<>();

        for (Relation r : relations) {
            g.addVertex(r.from);
            g.addVertex(r.to);
            DefaultEdge e = g.getEdge(r.from, r.to);
            if (e == null) {
                e = g.addEdge(r.from, r.to);
                edgeLabel.put(e, r.type);
                edgeType.put(e,  r.type);
            } else {
                // 合并多种关系
                String cur = edgeLabel.getOrDefault(e, "");
                Set<String> tags = new LinkedHashSet<>();
                if (!cur.isEmpty()) tags.addAll(Arrays.asList(cur.split("\\|")));
                tags.add(r.type);
                String merged = String.join("|", tags);
                edgeLabel.put(e, merged);
                // 优先级：betray > rival > couple > love > support
                edgeType.put(e, pickDominant(tags));
            }
        }

        // DOT 导出：ASCII 安全 ID，中文/英文原名放 label，边按关系加样式
        DOTExporter<String, DefaultEdge> exporter = new DOTExporter<>();
        Map<String, String> idMap = new LinkedHashMap<>();
        exporter.setVertexIdProvider(v -> idMap.computeIfAbsent(v, k -> "n" + (idMap.size() + 1)));

        exporter.setVertexAttributeProvider(v -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            m.put("label", DefaultAttribute.createAttribute(v));
            m.put("shape", DefaultAttribute.createAttribute("box"));
            m.put("style", DefaultAttribute.createAttribute("rounded"));
            return m;
        });

        exporter.setEdgeAttributeProvider(e -> {
            Map<String, Attribute> m = new LinkedHashMap<>();
            String label = edgeLabel.getOrDefault(e, "");
            if (!label.isEmpty()) m.put("label", DefaultAttribute.createAttribute(label));
            String kind = edgeType.getOrDefault(e, "support");
            Style s = EDGE_STYLE.getOrDefault(kind, EDGE_STYLE.get("support"));
            m.put("color", DefaultAttribute.createAttribute(s.color));
            m.put("penwidth", DefaultAttribute.createAttribute(String.valueOf(s.penwidth)));
            m.put("style", DefaultAttribute.createAttribute(s.style));
            m.put("arrowhead", DefaultAttribute.createAttribute(s.arrow));
            return m;
        });

        // 写 DOT
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outDotPath), StandardCharsets.UTF_8))) {
            exporter.exportGraph(g, w);
        }

        // 如可用，尝试调用 Graphviz 生成 PNG（失败不抛异常，不影响流程）
        if (outPngPath != null && !outPngPath.isBlank()) {
            tryRenderPngWithGraphviz(outDotPath, outPngPath);
        }
    }

    // 关系样式（Graphviz）
    private static final class Style {
        final String color, style, arrow; final int penwidth;
        Style(String color, int penwidth, String style, String arrow) {
            this.color=color; this.penwidth=penwidth; this.style=style; this.arrow=arrow;
        }
    }
    private static final Map<String, Style> EDGE_STYLE = new HashMap<>();
    static {
        EDGE_STYLE.put("love",    new Style("#d81b60", 2, "solid",  "normal"));   // 洋红
        EDGE_STYLE.put("couple",  new Style("#8e24aa", 3, "bold",   "normal"));   // 紫色粗线
        EDGE_STYLE.put("rival",   new Style("#3949ab", 2, "dashed", "normal"));   // 蓝色虚线
        EDGE_STYLE.put("betray",  new Style("#e53935", 3, "solid",  "vee"));      // 红色带尖箭头
        EDGE_STYLE.put("support", new Style("#00897b", 2, "dotted", "normal"));   // 绿色点线
    }

    private static String pickDominant(Collection<String> tags) {
        // 优先级：betray > rival > couple > love > support
        List<String> order = Arrays.asList("betray","rival","couple","love","support");
        for (String k : order) if (tags.contains(k)) return k;
        return "support";
    }

    // ======================= 文本关系抽取（中英双语/大小写不敏感） =======================
    private static final String EN_WORD = "[A-Za-z][A-Za-z0-9_]*";
    private static final String EN_NAME = EN_WORD + "(?:\\s+" + EN_WORD + "){0,2}";        // 最多 3 词名
    private static final String ZH_NAME = "[\\p{IsHan}A-Za-z0-9_]{1,16}";
    private static final String NAME    = "(?<A>(" + EN_NAME + ")|(" + ZH_NAME + "))";
    private static final String NAME_B  = "(?<B>(" + EN_NAME + ")|(" + ZH_NAME + "))";

    private static final List<Rule> RULES = Arrays.asList(
            // love
            new Rule(Pattern.compile(NAME + "\\s+(?:loves?|likes|is in love with)\\s+" + NAME_B, Pattern.CASE_INSENSITIVE), "love"),
            new Rule(Pattern.compile(NAME + "\\s*(喜欢|爱上了?|爱了|暗恋)\\s*" + NAME_B), "love"),

            // couple
            new Rule(Pattern.compile(NAME + "\\s+(?:is|are)?\\s*(?:together with|with|dating|married to|in a relationship with)\\s+" + NAME_B, Pattern.CASE_INSENSITIVE), "couple"),
            new Rule(Pattern.compile(NAME + "\\s*(和|与)\\s*" + NAME_B + "\\s*(在一起|成亲|结婚)"), "couple"),

            // rival（嫉妒/敌视/仇恨）
            new Rule(Pattern.compile(NAME + "\\s+(?:is\\s+)?(?:jealous|envious)\\s*(?:of\\s+)?" + NAME_B, Pattern.CASE_INSENSITIVE), "rival"),
            new Rule(Pattern.compile(NAME + "\\s+(?:hates?|dislikes?|is\\s+hostile\\s+(?:to|towards)|feuds?\\s+with|is\\s+against)\\s+" + NAME_B, Pattern.CASE_INSENSITIVE), "rival"),
            new Rule(Pattern.compile(NAME + "\\s*(嫉妒|吃醋|妒忌|敌视|仇恨)\\s*" + NAME_B), "rival"),

            // betray
            new Rule(Pattern.compile(NAME + "\\s+(?:betray(?:ed|s)?|backstabbed|framed|set\\s+up|cheated\\s+on)\\s+" + NAME_B, Pattern.CASE_INSENSITIVE), "betray"),
            new Rule(Pattern.compile(NAME + "\\s*(背叛|陷害|害)了?\\s*" + NAME_B), "betray"),

            // support
            new Rule(Pattern.compile(NAME + "\\s+(?:supports?|helps?|protects?|backs|stands\\s+by)\\s+" + NAME_B, Pattern.CASE_INSENSITIVE), "support"),
            new Rule(Pattern.compile(NAME + "\\s*(支持|帮助|维护|偏向)\\s*" + NAME_B), "support")
    );

    private static List<Relation> extractRelations(String text) {
        String normalized = text
                .replaceAll("[，、；;]", " ")
                .replaceAll("[。！？!?]", ". ");
        normalized = normalized.replaceAll("\\.(?=\\S)", ". ");
        normalized = normalized.replaceAll("\\s{2,}", " ").trim();

        List<Relation> out = new ArrayList<>();
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(normalized);
            while (m.find()) {
                String a = m.group("A");
                String b = m.group("B");
                if (a != null && b != null) {
                    a = a.trim();
                    b = b.trim();
                    if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                        out.add(new Relation(a, b, r.type));
                    }
                }
            }
        }
        return out;
    }

    // ======================= 时间线 =======================
    private static class TimelineEvent {
        LocalDate date; String desc;
        TimelineEvent(LocalDate d, String s) { date=d; desc=s; }
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
        System.out.println("—— Timeline ——");
        for (TimelineEvent e : events) {
            System.out.println(e.date + "  " + e.desc);
        }
    }

    // ======================= CLI 解析 =======================
    private static Map<String, String> parseArgs(String[] arr) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String a : arr) {
            if (a.startsWith("--")) {
                int i = a.indexOf('=');
                if (i > 2) m.put(a.substring(2, i), trimQuotes(a.substring(i + 1)));
                else m.put(a.substring(2), "true");
            }
        }
        return m;
    }
    private static String trimQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ======================= Graphviz PNG 渲染（可用则调用） =======================
    private static void tryRenderPngWithGraphviz(String dotPath, String pngPath) {
        List<String> candidates = new ArrayList<>();
        candidates.add("dot"); // PATH 中已可见
        // 常见 Windows 安装路径（可按需补充）
        candidates.add("C:\\Program Files\\Graphviz\\bin\\dot.exe");
        candidates.add("C:\\Program Files (x86)\\Graphviz\\bin\\dot.exe");

        for (String exe : candidates) {
            try {
                Process p = new ProcessBuilder(exe, "-Tpng", dotPath, "-o", pngPath)
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) { /* consume */ }
                }
                int code = p.waitFor();
                if (code == 0 && new File(pngPath).exists()) return;
            } catch (Exception ignore) {}
        }
        // 没有可用 Graphviz：静默跳过
    }

    // ======================= 数据结构 =======================
    private static class Relation {
        final String from, to, type;
        Relation(String f, String t, String ty) { this.from=f; this.to=t; this.type=ty; }
    }
    private static class Rule {
        final Pattern pattern; final String type;
        Rule(Pattern p, String t) { this.pattern=p; this.type=t; }
    }
}

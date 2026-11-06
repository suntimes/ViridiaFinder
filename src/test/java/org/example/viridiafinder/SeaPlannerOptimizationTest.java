package org.example.viridiafinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class SeaPlannerOptimizationTest {
    static void run(BiConsumer<Boolean, String> check, Consumer<String> report) throws IOException {
        byte[] water = new byte[16 * 12];
        Arrays.fill(water, (byte)2);
        var openSea = new SeaMap(0, 0, 4, 16, 12, water);
        var west = new Survey.Pos(2, 80, 2);
        var east = new Survey.Pos(38, 80, 2);
        var candidate = new Survey.Candidate(1, new Survey.Pos(30, 38, 2));
        var fromWest = SeaPlanner.plan(openSea, west, List.of(candidate), 8);
        var fromEast = SeaPlanner.plan(openSea, east, List.of(candidate), 8);
        check.accept(fromWest.length() == 20 && fromEast.length() == 0
            && fromWest.stops().get(0).path().get(0).equals(west)
            && fromEast.stops().get(0).path().equals(List.of(east)),
            "开口海路使用实际起点，进入覆盖范围即可停靠，起点已覆盖时航程为零");

        for (int z = 0; z < 12; z++) water[z * 16 + 7] = 0;
        var divided = new SeaMap(0, 0, 4, 16, 12, water);
        var isolated = new Survey.Candidate(2, new Survey.Pos(42, 80, 2));
        var local = new Survey.Candidate(1, new Survey.Pos(10, 80, 2));
        var blocked = SeaPlanner.plan(divided, west, List.of(local, isolated), 0);
        check.accept(blocked.unresolved().equals(List.of(2)) && covered(blocked).equals(Set.of(1)),
            "未知海域隔开的候选仍明确列为未覆盖");

        byte[] coast = new byte[100];
        coast[99] = 2;
        var inlandMap = new SeaMap(0, 0, 4, 100, 1, coast);
        var farInland = SeaPlanner.plan(inlandMap, west, List.of(new Survey.Candidate(1,inlandMap.point(99))),8);
        check.accept(farInland.unresolved().isEmpty() && farInland.stops().size()==1
            && farInland.stops().get(0).path().get(0).equals(inlandMap.point(99))
            && inlandMap.oceanEntry(new Survey.Pos(-5000,80,0))<0,
            "图内陆上起点可从超过256格的最近海面开始规划，图外起点明确不支持");

        var candidates = new ArrayList<Survey.Candidate>();
        try (var input = SeaPlannerOptimizationTest.class.getResourceAsStream("/assets/viridiafinder/candidates.csv")) {
            if (input == null) throw new IOException("缺少候选数据库");
            var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] xyz = line.split(",");
                var pos = new Survey.Pos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
                candidates.add(new Survey.Candidate(candidates.size() + 1, pos));
            }
        }
        var sea = SeaMap.load();
        var usual = new Survey.Pos(-763, 84, 1321);
        var northern = new Survey.Pos(-546, 41, -234);
        // Ten complete observed rings, one ring of margin, and an eight-block box scan radius.
        int radius = (10 - 1) * 16 - 8;
        benchmark(check, report, sea, usual, candidates, radius, "常用起点全169点");
        benchmark(check, report, sea, usual, candidates, (10-1)*16-16, "扩大范围±16全169点");
        benchmark(check, report, sea, northern, candidates, radius, "北部起点全169点");
        var subset = candidates.stream().filter(c -> c.id % 5 == 0).limit(32).toList();
        benchmark(check, report, sea, northern, subset, radius, "北部起点模拟剩余32点");
    }

    private static Set<Integer> covered(SeaPlanner.Plan plan) {
        Set<Integer> ids = new HashSet<>();
        for (var stop : plan.stops()) ids.addAll(stop.candidates());
        return ids;
    }

    private static void benchmark(BiConsumer<Boolean,String> check, Consumer<String> report, SeaMap map,
                                  Survey.Pos start, List<Survey.Candidate> candidates, int radius, String label) {
        var baseline = SeaPlanner.greedyPlan(map, start, candidates, radius);
        var refined = SeaPlanner.improve(map, baseline, candidates);
        var route = refined.plan();
        var positions = new HashMap<Integer, Survey.Pos>();
        for (var candidate : candidates) positions.put(candidate.id, candidate.pos);
        var visited = new HashSet<Integer>();
        var previous = map.point(map.nearest(start, 256));
        boolean safe = true, complete = true;
        double measured = 0;
        for (var stop : route.stops()) {
            for (var point : stop.path()) {
                safe &= map.clear(map.cell(previous), map.cell(point));
                measured += RouteOptimizer.distance(previous, point);
                previous = point;
            }
            for (int id : stop.candidates()) {
                var p = positions.get(id);
                complete &= p != null && Math.abs(previous.x() - p.x()) <= radius
                    && Math.abs(previous.z() - p.z()) <= radius && visited.add(id);
            }
        }
        check.accept(!route.stops().isEmpty() && route.stops().get(0).path().get(0).equals(map.point(map.nearest(start,256)))
            && safe && Math.abs(measured - route.length()) < 1e-5, label + "的航程从实测起点最近海格起算，每段水路连续可通行");
        check.accept(complete && visited.equals(covered(baseline)) && route.unresolved().equals(baseline.unresolved())
            && visited.size() + route.unresolved().size() == candidates.size() && route.radius() == radius,
            label + "保持原覆盖集合，每个候选只分配给半径内的一站");
        check.accept(route.length() <= baseline.length() && refined.connectors() <= SeaPlanner.MAX_CONNECTORS
            && refined.detours() <= SeaPlanner.MAX_DETOURS, label + "总航程不增加，细化寻路次数在预算内");
        String metrics = String.format(Locale.ROOT,
            "%s，玩家起点(%d,%d)，覆盖半径%d格：原贪心 %.1f格 → 优化 %.1f格，减少%.2f%%，%d站，覆盖%d点，未覆盖%d点，新增连接评估%d次、绕障A* %d次。静态海图测距。",
            label, start.x(), start.z(), radius, baseline.length(), route.length(),
            100 * (baseline.length() - route.length()) / baseline.length(), route.stops().size(), visited.size(),
            route.unresolved().size(), refined.connectors(), refined.detours());
        report.accept(metrics);
        System.out.println(metrics);
    }
}

package org.example.viridiafinder;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

final class CompactHudTest {
    static void run(BiConsumer<Boolean, String> check) {
        var player = new Survey.Pos(-700, 80, 1200);
        var candidates = IntStream.range(0, 169)
            .mapToObj(i -> new Survey.Pos(player.x() + i, player.y(), player.z())).toList();
        var survey = new Survey(candidates, new Survey.Settings());
        for (int i = 0; i < 137; i++) survey.candidates.get(i).visited = true;

        record Bearing(int dx, int dz, String expected) {}
        var bearings = List.of(
            new Bearing(0, -3, "北 3 格"), new Bearing(2, -2, "东北 3 格"),
            new Bearing(3, 0, "东 3 格"), new Bearing(3, 4, "东南 5 格"),
            new Bearing(0, 3, "南 3 格"), new Bearing(-2, 2, "西南 3 格"),
            new Bearing(-3, 0, "西 3 格"), new Bearing(-3, -4, "西北 5 格")
        );
        for (Bearing bearing : bearings) {
            var destination = new Survey.Pos(player.x() + bearing.dx(), -40, player.z() + bearing.dz());
            var expected = List.of(bearing.expected(), "剩余 32/169");
            check.accept(CompactHud.lines(player, destination, survey).equals(expected),
                "简洁导航按水平坐标显示" + bearing.expected() + "和剩余候选数");
        }
        var overhead = new Survey.Pos(player.x(), 320, player.z());
        check.accept(CompactHud.lines(player, overhead, survey).equals(List.of("原地 0 格", "剩余 32/169")),
            "目标在正上方时显示原地和零水平距离");
        check.accept(CompactHud.lines(player, null, survey).equals(List.of("剩余 32/169")),
            "等待导航目标时只显示剩余候选数");

        var entrance = new Survey.Pos(player.x() - 8, 80, player.z());
        var turn = new Survey.Pos(player.x() - 8, 80, player.z() + 16);
        var end = new Survey.Pos(player.x() + 32, 80, player.z());
        var stop = new SeaPlanner.Stop(List.of(entrance, turn, end), List.of(169));
        var route = new SeaRouteProgress(new SeaPlanner.Plan(List.of(stop), List.of(), 88, 64));
        var east = List.of("东 32 格", "剩余 32/169");
        for (int i = 0; i < stop.path().size(); i++) {
            route.waypoint = i;
            check.accept(CompactHud.lines(player, route.destination(), survey).equals(east),
                "航点索引 " + i + " 时仍显示本站终点");
        }
        var newEnd = new Survey.Pos(player.x(), 80, player.z() - 24);
        route.rejoined(List.of(player, turn, newEnd));
        check.accept(CompactHud.lines(player, route.destination(), survey).equals(List.of("北 24 格", "剩余 32/169")),
            "重新接入海路后导航指向新终点");
        survey.candidates.forEach(candidate -> candidate.visited = true);
        route.advanceCompleted(survey);
        check.accept(CompactHud.lines(player, route.destination(), survey).equals(List.of("剩余 0/169")),
            "本轮巡查完成后目标消失，剩余数归零");
    }
}

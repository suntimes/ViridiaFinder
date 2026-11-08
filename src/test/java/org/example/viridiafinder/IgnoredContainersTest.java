package org.example.viridiafinder;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class IgnoredContainersTest {
    private static final String SCOPE = "server-a|dimension-a|instance-a";

    private static final class Fixture implements Survey.View {
        final Map<Survey.Pos, Survey.Kind> blocks = new HashMap<>();
        final Map<Survey.Pos, Integer> readsByPosition = new HashMap<>();
        boolean loaded = true;
        int reads;

        public boolean loaded(int chunkX, int chunkZ) { return loaded; }
        public int bottom() { return 0; }
        public int top() { return 128; }
        public Survey.Kind read(Survey.Pos position) {
            if (!loaded(position.x() >> 4, position.z() >> 4))
                throw new AssertionError("忽略功能读取了未加载区块");
            if (position.y() < bottom() || position.y() >= top())
                throw new AssertionError("忽略功能读取超出世界高度");
            reads++;
            readsByPosition.merge(position, 1, Integer::sum);
            return blocks.getOrDefault(position, Survey.Kind.NONE);
        }
    }

    static void run(BiConsumer<Boolean, String> check) {
        checkExactCoordinatesAndScanning(check);
        checkDisplayAndRestore(check);
        checkPersistenceAndScope(check);
        checkUnknownAndLegacy(check);
        checkMarkCorrection(check);
    }

    private static Survey.Settings settings(int radius) {
        var settings = new Survey.Settings();
        settings.radius = radius;
        settings.below = 1;
        settings.above = 1;
        return settings;
    }

    private static void pass(Survey survey, Fixture fixture, Consumer<Survey.Found> discovered) {
        long previous = survey.passes;
        int ticks = 0;
        while (survey.passes == previous) {
            survey.tick(fixture, 1, Long.MAX_VALUE, discovered);
            if (++ticks > 100_000) throw new AssertionError("忽略功能测试扫描没有完成");
        }
    }

    private static void checkExactCoordinatesAndScanning(BiConsumer<Boolean, String> check) {
        var ignored = new Survey.Pos(8, 64, 8);
        var east = new Survey.Pos(9, 64, 8);
        var above = new Survey.Pos(8, 65, 8);
        var south = new Survey.Pos(8, 64, 9);
        var survey = new Survey(List.of(ignored, east), settings(2), SCOPE);
        survey.setIgnored(ignored, true);
        survey.setIgnored(ignored, true);
        check.accept(survey.isIgnored(ignored) && !survey.isIgnored(east)
            && !survey.isIgnored(above) && !survey.isIgnored(south)
            && survey.ignoredPositions().equals(List.of(ignored)),
            "忽略精确匹配XYZ，重复标记不增加记录，邻近及不同高度箱子不受影响");
        check.accept(survey.candidates.stream().noneMatch(candidate -> candidate.visited)
            && survey.route(ignored).size() == 2,
            "忽略一个箱子不会标记候选已巡查或从路线移除候选");

        var fixture = new Fixture();
        fixture.blocks.put(ignored, Survey.Kind.CHEST);
        fixture.blocks.put(east, Survey.Kind.TRAPPED);
        fixture.blocks.put(above, Survey.Kind.CHEST);
        fixture.blocks.put(south, Survey.Kind.CHEST);
        Set<Survey.Pos> discoveries = new HashSet<>();
        pass(survey, fixture, found -> discoveries.add(found.pos));
        check.accept(discoveries.equals(Set.of(east, above, south)),
            "重叠候选均抑制同一忽略箱子的发现通知，附近普通箱及陷阱箱仍发现");
        check.accept(survey.candidates.stream().allMatch(candidate -> {
            var found = candidate.found.get(ignored.key());
            return found != null && found.present && found.kind == Survey.Kind.CHEST;
        }), "忽略箱子仍保留两个候选的真实扫描记录");
        check.accept(fixture.reads == 150 && fixture.readsByPosition.get(ignored) == 2
            && survey.candidates.stream().allMatch(candidate -> candidate.checked == 75
                && survey.fullyScanned(candidate, fixture) && !candidate.visited),
            "忽略不跳过方块读取，完整扫描仍按实际覆盖完成且不擅自修改巡查状态");
        var highlights = ContainerHighlights.collect(survey, fixture, ignored, 96, 256);
        check.accept(highlights.stream().map(ContainerHighlights.Highlight::pos).collect(Collectors.toSet())
            .equals(Set.of(east, above, south)) && highlights.size() == 3,
            "双候选重叠时忽略箱子不泄漏高亮，邻近容器按实际坐标去重显示");
    }

    private static void checkDisplayAndRestore(BiConsumer<Boolean, String> check) {
        var position = new Survey.Pos(8, 64, 8);
        var neighbor = new Survey.Pos(20, 64, 8);
        var survey = new Survey(List.of(position, new Survey.Pos(9, 64, 8)), settings(16), SCOPE);
        var fixture = new Fixture();
        fixture.blocks.put(position, Survey.Kind.CHEST);
        for (var candidate : survey.candidates)
            survey.observe(candidate, position, Survey.Kind.CHEST, found -> {});
        survey.setIgnored(position, true);
        var hidden = SurveyMinimap.dots(position, survey);
        check.accept(!hidden.isEmpty() && hidden.stream().noneMatch(dot -> dot.style().container)
            && hidden.stream().allMatch(dot -> dot.style() == SurveyMinimap.Style.CANDIDATE)
            && ContainerHighlights.collect(survey, fixture, position, 96, 256).isEmpty(),
            "忽略移除小地图与世界高亮中的箱子层，同时保留历史候选点");

        fixture.blocks.put(neighbor, Survey.Kind.TRAPPED);
        survey.observe(survey.candidates.get(0), neighbor, Survey.Kind.TRAPPED, found -> {});
        check.accept(SurveyMinimap.dots(position, survey).stream().filter(dot -> dot.style().container)
            .map(SurveyMinimap.Dot::style).toList().equals(List.of(SurveyMinimap.Style.TRAPPED)),
            "忽略候选附近普通箱时，相邻独立陷阱箱地图标记继续显示");
        survey.setIgnored(position, false);
        var restoredHighlights = ContainerHighlights.collect(survey, fixture, position, 96, 256);
        var restoredDots = SurveyMinimap.dots(position, survey);
        check.accept(!survey.isIgnored(position) && survey.ignoredPositions().isEmpty()
            && restoredHighlights.size() == 2
            && restoredHighlights.stream().filter(highlight -> highlight.pos().equals(position)).count() == 1
            && restoredDots.stream().filter(dot -> dot.style() == SurveyMinimap.Style.CHEST).count() == 1,
            "取消忽略立即恢复现有实时箱子显示，双候选不会生成重复高亮或地图标记");

        survey.invalidateChunk(0, 0);
        survey.setIgnored(position, true);
        check.accept(SurveyMinimap.dots(position, survey).stream()
            .noneMatch(dot -> dot.style() == SurveyMinimap.Style.HISTORICAL_CHEST),
            "区块卸载后的忽略箱子不会以历史空心标记重新出现");
        survey.setIgnored(position, false);
        check.accept(SurveyMinimap.dots(position, survey).stream()
            .anyMatch(dot -> dot.style() == SurveyMinimap.Style.HISTORICAL_CHEST)
            && ContainerHighlights.collect(survey, fixture, position, 96, 256).stream()
                .noneMatch(highlight -> highlight.pos().equals(position)),
            "恢复旧观察只恢复历史地图记录，不把已卸载箱子当成实时高亮");
    }

    private static void checkPersistenceAndScope(BiConsumer<Boolean, String> check) {
        var position = new Survey.Pos(-17, 64, -1);
        var second = new Survey.Pos(-17, 65, -1);
        var config = settings(1);
        var firstRound = new Survey(List.of(position), config, SCOPE);
        firstRound.setIgnored(second, true);
        firstRound.setIgnored(position, true);
        firstRound.candidates.get(0).visited = true;
        var nextRound = new Survey(List.of(position), config, SCOPE);
        check.accept(nextRound.isIgnored(position) && nextRound.isIgnored(second)
            && nextRound.ignoredPositions().equals(List.of(position, second))
            && nextRound.candidates.get(0).found.isEmpty() && !nextRound.candidates.get(0).visited,
            "新一轮重建扫描器仍保留精确忽略坐标，不沿用上一轮扫描与巡查状态");

        var gson = new Gson();
        var restoredConfig = gson.fromJson(gson.toJson(config), Survey.Settings.class);
        var restored = new Survey(List.of(position), restoredConfig, SCOPE);
        check.accept(restored.isIgnored(position) && restored.isIgnored(second)
            && restored.ignoredPositions().equals(firstRound.ignoredPositions()),
            "忽略配置经Gson存盘与读取往返后保留负坐标和高度差");
        boolean isolated = true;
        for (var scope : List.of("server-b|dimension-a|instance-a",
            "server-a|dimension-b|instance-a", "server-a|dimension-a|instance-b")) {
            var other = new Survey(List.of(position), restoredConfig, scope);
            isolated &= !other.isIgnored(position) && other.ignoredPositions().isEmpty();
            other.setIgnored(position, true);
            other.setIgnored(position, false);
            isolated &= restored.isIgnored(position) && restored.isIgnored(second);
        }
        check.accept(isolated, "不同服务器、维度或实例的忽略名单分别隔离，其他范围取消忽略不影响当前范围");
        var otherInstance = new Survey(List.of(position), restoredConfig, "server-a|dimension-a|instance-visible");
        var fixture = new Fixture();
        fixture.blocks.put(position, Survey.Kind.CHEST);
        Set<Survey.Pos> discoveries = new HashSet<>();
        otherInstance.observe(otherInstance.candidates.get(0), position, Survey.Kind.CHEST,
            found -> discoveries.add(found.pos));
        check.accept(restored.isIgnored(position) && discoveries.equals(Set.of(position))
            && ContainerHighlights.collect(otherInstance, fixture, position, 96, 256).size() == 1
            && SurveyMinimap.dots(position, otherInstance).stream()
                .anyMatch(dot -> dot.style() == SurveyMinimap.Style.CHEST),
            "同一XYZ在其他实例仍正常发现、世界高亮及小地图显示");
        restored.setIgnored(position, false);
        var reopened = new Survey(List.of(position),
            gson.fromJson(gson.toJson(restoredConfig), Survey.Settings.class), SCOPE);
        check.accept(!reopened.isIgnored(position) && reopened.isIgnored(second)
            && firstRound.isIgnored(position),
            "取消忽略后的配置可再次持久化，其他高度记录和原始配置副本保持独立");
    }

    private static void checkUnknownAndLegacy(BiConsumer<Boolean, String> check) {
        var position = new Survey.Pos(-1, 64, -1);
        var survey = new Survey(List.of(position), settings(1), SCOPE);
        survey.setIgnored(position, true);
        var fixture = new Fixture();
        fixture.loaded = false;
        fixture.blocks.put(position, Survey.Kind.CHEST);
        Set<Survey.Pos> discoveries = new HashSet<>();
        var route = new Survey.RoutePlan(survey.route(position));
        pass(survey, fixture, found -> discoveries.add(found.pos));
        var candidate = survey.candidates.get(0);
        check.accept(fixture.reads == 0 && candidate.checked == 0 && candidate.unknown == 27
            && candidate.found.isEmpty() && discoveries.isEmpty() && !survey.fullyScanned(candidate, fixture),
            "候选含忽略坐标时仍尊重未加载守卫，未知27格不读取也不误报扫描完成");
        check.accept(route.advance(survey, fixture) == 0 && route.current() == candidate
            && !candidate.visited && survey.isIgnored(position),
            "忽略未加载位置不会让路线跳站或将候选自动标成已巡查");
        fixture.loaded = true;
        pass(survey, fixture, found -> discoveries.add(found.pos));
        check.accept(fixture.reads == 27 && discoveries.isEmpty()
            && candidate.found.get(position.key()).present && survey.fullyScanned(candidate, fixture),
            "原未知区块加载后正常完成扫描，忽略箱仍记录且保持无发现通知");

        var gson = new Gson();
        boolean compatible = true;
        for (String json : List.of("{}", "{\"ignoredContainers\":null}")) {
            var config = gson.fromJson(json, Survey.Settings.class);
            var legacy = new Survey(List.of(position), config);
            compatible &= legacy.ignoredPositions().isEmpty() && !legacy.isIgnored(position);
            legacy.setIgnored(position, true);
            compatible &= legacy.isIgnored(position);
            legacy.setIgnored(position, false);
            compatible &= legacy.ignoredPositions().isEmpty();
        }
        check.accept(compatible, "旧配置缺少忽略字段或字段为null时兼容两参数构造，仍可新增及恢复忽略");
    }

    private static void checkMarkCorrection(BiConsumer<Boolean, String> check) {
        var position = new Survey.Pos(8, 64, 8);
        var legitimateClaim = new Survey.Pos(12, 64, 8);
        var legitimateConfirmation = new Survey.Pos(12, 64, 9);
        var survey = new Survey(List.of(position, new Survey.Pos(9, 64, 8),
            new Survey.Pos(10, 64, 8), new Survey.Pos(11, 64, 8)), settings(8), SCOPE);
        var marks = List.of(Survey.Mark.CLAIMED, Survey.Mark.CLAIMED,
            Survey.Mark.CONFIRMED, Survey.Mark.SUSPECT);
        for (int i = 0; i < survey.candidates.size(); i++) {
            var candidate = survey.candidates.get(i);
            survey.observe(candidate, position, Survey.Kind.CHEST, found -> {});
            candidate.found.get(position.key()).mark = marks.get(i);
            candidate.visited = i != 3;
        }
        var first = survey.candidates.get(0);
        var withClaim = survey.candidates.get(1);
        var withConfirmation = survey.candidates.get(2);
        survey.observe(withClaim, legitimateClaim, Survey.Kind.CHEST, found -> {});
        withClaim.found.get(legitimateClaim.key()).mark = Survey.Mark.CLAIMED;
        survey.observe(withConfirmation, legitimateConfirmation, Survey.Kind.TRAPPED, found -> {});
        withConfirmation.found.get(legitimateConfirmation.key()).mark = Survey.Mark.CONFIRMED;

        survey.setIgnored(position, true);
        check.accept(survey.candidates.stream().allMatch(candidate ->
            candidate.found.get(position.key()).mark == Survey.Mark.CONTAINER),
            "纠错忽略将同坐标所有候选中的旧已领取、确认和疑似标记改回普通容器");
        check.accept(!first.visited && withClaim.visited && withConfirmation.visited
            && !survey.candidates.get(3).visited && survey.route(position).contains(first),
            "仅纠正唯一旧已领取标记时重新开放候选复核，其他巡查状态不擅自清除");
        check.accept(withClaim.found.get(legitimateClaim.key()).mark == Survey.Mark.CLAIMED
            && withConfirmation.found.get(legitimateConfirmation.key()).mark == Survey.Mark.CONFIRMED
            && !survey.isIgnored(legitimateClaim) && !survey.isIgnored(legitimateConfirmation),
            "忽略纠错不更改其他XYZ的有效已领取及确认标记，有其他已领取的候选继续保持已巡查");

        survey.setIgnored(position, false);
        var fixture = new Fixture();
        fixture.blocks.put(position, Survey.Kind.CHEST);
        fixture.blocks.put(legitimateClaim, Survey.Kind.CHEST);
        fixture.blocks.put(legitimateConfirmation, Survey.Kind.TRAPPED);
        check.accept(survey.candidates.stream().allMatch(candidate ->
                candidate.found.get(position.key()).mark == Survey.Mark.CONTAINER)
            && ContainerHighlights.collect(survey, fixture, position, 96, 256).stream()
                .filter(highlight -> highlight.pos().equals(position)).count() == 1
            && !first.visited && withClaim.visited,
            "取消纠错忽略恢复普通箱子显示，不恢复错误宝藏标记或撤销待复核状态");
        first.visited = true;
        survey.setIgnored(position, true);
        check.accept(first.visited && withClaim.visited && withConfirmation.visited
            && !survey.candidates.get(3).visited,
            "再次忽略已纠正的普通容器时保留原有扫描巡查状态");
    }
}

package org.example.viridiafinder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.example.viridiafinder.SurveyMinimap.Style.*;

final class SurveyMinimapTest {
    static void run(BiConsumer<Boolean, String> check) {
        checkProjection(check);
        checkObservations(check);
        checkOverlaps(check);
    }

    private static void checkProjection(BiConsumer<Boolean, String> check) {
        var player = new Survey.Pos(-300, 80, -200);
        var survey = new Survey(List.of(
            new Survey.Pos(-300, -20, -296), // north at a different height
            new Survey.Pos(-204, 80, -200),  // east
            new Survey.Pos(-492, 80, -392),  // northwest corner
            new Survey.Pos(-109, 80, -9),    // southeast corner
            new Survey.Pos(-493, 80, -200), new Survey.Pos(-108, 80, -200),
            new Survey.Pos(-300, 80, -393), new Survey.Pos(-300, 80, -8)
        ), new Survey.Settings());
        survey.candidates.get(1).visited = true;
        var dots = SurveyMinimap.dots(player, survey);
        check.accept(dots.contains(new SurveyMinimap.Dot(52, 26, CANDIDATE))
            && dots.contains(new SurveyMinimap.Dot(78, 52, VISITED)), "小地图按水平坐标投影，北在上、东在右，已巡查候选仍显示");
        check.accept(dots.size() == 4 && dots.contains(new SurveyMinimap.Dot(0, 0, CANDIDATE))
            && dots.contains(new SurveyMinimap.Dot(103, 103, CANDIDATE)), "384格地图裁掉四边界外点，保留首末像素");
        check.accept(dots.stream().noneMatch(d -> d.style().container), "只有候选记录时小地图仅绘制候选点");
    }

    private static void checkObservations(BiConsumer<Boolean, String> check) {
        var player = new Survey.Pos(0, 80, 0);
        // Even an off-map candidate can have a chest inside the map edge.
        var settings = new Survey.Settings();
        settings.below = 16;
        var survey = new Survey(List.of(new Survey.Pos(194, 80, 0), player), settings);
        add(survey, 0, new Survey.Pos(191, 73, 0), Survey.Kind.CHEST, Survey.Mark.CONTAINER);
        add(survey, 1, new Survey.Pos(8, 64, 0), Survey.Kind.TRAPPED, Survey.Mark.CONTAINER);
        var suspect = new Survey.Pos(0, 64, 8);
        var confirmed = new Survey.Pos(8, 64, 8);
        add(survey, 1, suspect, Survey.Kind.CHEST, Survey.Mark.SUSPECT);
        add(survey, 1, confirmed, Survey.Kind.CHEST, Survey.Mark.CONFIRMED);
        add(survey, 1, new Survey.Pos(-8, 64, 0), Survey.Kind.CHEST, Survey.Mark.CLAIMED);
        var legacy = new Survey.Found(new Survey.Pos(0, 64, -8), Survey.Kind.BARREL);
        legacy.present = true;
        survey.candidates.get(1).found.put(legacy.pos.key(), legacy);

        var observed = SurveyMinimap.dots(player, survey);
        check.accept(observed.contains(new SurveyMinimap.Dot(103, 52, CHEST))
            && styles(observed).equals(Set.of(CANDIDATE, CHEST, TRAPPED, SUSPECT, CONFIRMED, CLAIMED)),
            "小地图按箱子实际位置绘制两类箱子和人工标记，兼容旧木桶记录");
        check.accept(CLAIMED.historical && !CHEST.historical && !TRAPPED.historical
            && !SUSPECT.historical && !CONFIRMED.historical, "已领取与当前观察到的箱子使用不同绘制方式");

        survey.invalidateChunk(0, 0); // unloading the chunk clears present for all observations in it
        survey.observe(survey.candidates.get(0), new Survey.Pos(191, 73, 0), Survey.Kind.NONE, ignored -> {});
        var historical = SurveyMinimap.dots(player, survey);
        check.accept(styles(historical).equals(Set.of(CANDIDATE, HISTORICAL_CHEST, HISTORICAL_TRAPPED,
            HISTORICAL_SUSPECT, HISTORICAL_CONFIRMED, CLAIMED))
            && historical.stream().filter(d -> d.style().container).allMatch(d -> d.style().historical),
            "区块卸载或箱子消失后，各类观察保留为历史空心标记");
        survey.observe(survey.candidates.get(1), suspect, Survey.Kind.CHEST, ignored -> {});
        var refreshed = styles(SurveyMinimap.dots(player, survey));
        check.accept(refreshed.contains(SUSPECT) && !refreshed.contains(HISTORICAL_SUSPECT)
            && refreshed.contains(HISTORICAL_CONFIRMED), "重新观察单个箱子时只恢复对应标记，保留人工状态");
        settings.radius = 2;
        var narrowed = styles(SurveyMinimap.dots(player, survey));
        check.accept(survey.candidates.get(1).found.get(suspect.key()).present
            && narrowed.contains(HISTORICAL_SUSPECT) && !narrowed.contains(SUSPECT),
            "缩小扫描范围后，范围外尚有上次观察记录的箱子改用历史标记");
        check.accept(Arrays.stream(SurveyMinimap.Style.values()).map(s -> s.color).distinct().count()
            == SurveyMinimap.Style.values().length, "候选、箱子、人工状态及历史各有可区分的颜色");
    }

    private static void checkOverlaps(BiConsumer<Boolean, String> check) {
        var player = new Survey.Pos(32, 80, 32);
        var survey = new Survey(List.of(player, new Survey.Pos(33, 80, 32)), new Survey.Settings());
        survey.candidates.get(0).visited = true;
        var chest = new Survey.Pos(32, 78, 32);
        add(survey, 0, chest, Survey.Kind.CHEST, Survey.Mark.CLAIMED);
        add(survey, 1, chest, Survey.Kind.CHEST, Survey.Mark.CONTAINER);
        var expected = List.of(new SurveyMinimap.Dot(52, 52, CANDIDATE), new SurveyMinimap.Dot(52, 52, CLAIMED));
        check.accept(SurveyMinimap.dots(player, survey).equals(expected), "同像素保留候选与箱子两层，同一箱子的已领取标记优先于重复观察");
        Collections.reverse(survey.candidates);
        check.accept(SurveyMinimap.dots(player, survey).equals(expected), "调整重叠候选遍历顺序后显示相同标记");
        add(survey, 0, new Survey.Pos(32, 79, 32), Survey.Kind.TRAPPED, Survey.Mark.CONTAINER);
        check.accept(SurveyMinimap.dots(player, survey).equals(List.of(new SurveyMinimap.Dot(52, 52, CANDIDATE),
            new SurveyMinimap.Dot(52, 52, TRAPPED))), "同像素不同高度的箱子优先显示尚可检查的实时观察");
    }

    private static void add(Survey survey, int index, Survey.Pos pos, Survey.Kind kind, Survey.Mark mark) {
        var candidate = survey.candidates.get(index);
        survey.observe(candidate, pos, kind, ignored -> {});
        candidate.found.get(pos.key()).mark = mark;
    }

    private static Set<SurveyMinimap.Style> styles(List<SurveyMinimap.Dot> dots) {
        return dots.stream().map(SurveyMinimap.Dot::style).collect(Collectors.toSet());
    }
}

package org.example.viridiafinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SurveyMinimap {
    static final int SIZE = 104;
    static final int CENTER = SIZE / 2;
    private static final int SPAN = 384;

    // Back to front on screen. The separate record priority resolves multiple reports of one block.
    enum Style {
        VISITED(0xFF707C86, false, 0),
        CANDIDATE(0xFFACB8C3, false, 0),
        HISTORICAL_CHEST(0xFFB5A076, true, 1),
        HISTORICAL_TRAPPED(0xFFC28D80, true, 2),
        HISTORICAL_SUSPECT(0xFFB599C0, true, 5),
        HISTORICAL_CONFIRMED(0xFF8CAFB8, true, 7),
        CLAIMED(0xFF7DA989, true, 9),
        CHEST(0xFFFFD768, false, 3),
        TRAPPED(0xFFFF826B, false, 4),
        SUSPECT(0xFFEAA3FF, false, 6),
        CONFIRMED(0xFF7BEAFF, false, 8);

        final int color;
        final boolean historical;
        final boolean container;
        private final int recordPriority;

        Style(int color, boolean historical, int recordPriority) {
            this.color = color;
            this.historical = historical;
            this.recordPriority = recordPriority;
            this.container = recordPriority != 0;
        }

        static Style forChest(Survey.Found found, boolean current) {
            if (found.kind != Survey.Kind.CHEST && found.kind != Survey.Kind.TRAPPED) return null;
            return switch (found.mark) {
                case CLAIMED -> CLAIMED;
                case CONFIRMED -> current ? CONFIRMED : HISTORICAL_CONFIRMED;
                case SUSPECT -> current ? SUSPECT : HISTORICAL_SUSPECT;
                case CONTAINER -> found.kind == Survey.Kind.TRAPPED
                    ? (current ? TRAPPED : HISTORICAL_TRAPPED)
                    : (current ? CHEST : HISTORICAL_CHEST);
            };
        }

        static Style forSameBlock(Style a, Style b) {
            return a.recordPriority >= b.recordPriority ? a : b;
        }

        static Style forSamePixel(Style a, Style b) {
            return a.compareTo(b) >= 0 ? a : b;
        }
    }

    record Dot(int x, int y, Style style) {}

    private SurveyMinimap() {}

    // Local pixel index, or -1 outside the half-open 384-by-384 block window.
    private static int pixel(Survey.Pos player, Survey.Pos point) {
        long x = (long) point.x() - player.x() + SPAN / 2;
        long z = (long) point.z() - player.z() + SPAN / 2;
        if (x < 0 || x >= SPAN || z < 0 || z >= SPAN) return -1;
        int column = (int) (x * SIZE / SPAN);
        int row = (int) (z * SIZE / SPAN);
        return row * SIZE + column;
    }

    static List<Dot> dots(Survey.Pos player, Survey survey) {
        Map<Integer, Style> candidates = new HashMap<>();
        Map<Survey.Pos, Style> chestsByBlock = new HashMap<>();
        for (var candidate : survey.candidates) {
            int position = pixel(player, candidate.pos);
            if (position >= 0) {
                candidates.merge(position, candidate.visited ? Style.VISITED : Style.CANDIDATE, Style::forSamePixel);
            }
            for (var found : candidate.found.values()) {
                if (survey.isIgnored(found.pos) || pixel(player, found.pos) < 0) continue;
                var style = Style.forChest(found, found.present && survey.inRange(candidate, found.pos));
                if (style != null) chestsByBlock.merge(found.pos, style, Style::forSameBlock);
            }
        }

        Map<Integer, Style> chestsByPixel = new HashMap<>();
        chestsByBlock.forEach((block, style) -> chestsByPixel.merge(pixel(player, block), style, Style::forSamePixel));
        List<Dot> result = new ArrayList<>(candidates.size() + chestsByPixel.size());
        candidates.forEach((p, style) -> result.add(new Dot(p % SIZE, p / SIZE, style)));
        chestsByPixel.forEach((p, style) -> result.add(new Dot(p % SIZE, p / SIZE, style)));
        result.sort(Comparator.comparing(Dot::style).thenComparingInt(Dot::y).thenComparingInt(Dot::x));
        return result;
    }
}

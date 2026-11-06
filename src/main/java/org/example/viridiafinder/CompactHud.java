package org.example.viridiafinder;

import java.util.List;

final class CompactHud {
    private static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    private CompactHud() {}

    static List<String> lines(Survey.Pos player, Survey.Pos destination, Survey survey) {
        int remaining = 0;
        for (Survey.Candidate candidate : survey.candidates) {
            if (!candidate.visited) remaining++;
        }
        String progress = "剩余 " + remaining + "/" + survey.candidates.size();
        if (destination == null) return List.of(progress);

        long distance = Math.round(RouteOptimizer.distance(player, destination));
        if (distance == 0) return List.of("原地 0 格", progress);

        double east = (double) destination.x() - player.x();
        double north = (double) player.z() - destination.z();
        int sector = (int) Math.round(Math.atan2(east, north) / (Math.PI / 4));
        String direction = DIRECTIONS[Math.floorMod(sector, DIRECTIONS.length)];
        return List.of(direction + " " + distance + " 格", progress);
    }
}

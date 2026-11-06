package org.example.viridiafinder;

import java.util.List;

/** 扫描站按实际完成状态推进；海路航点距离只用于转弯导航。 */
final class SeaRouteProgress {
    final SeaPlanner.Plan plan;
    int stop,waypoint;
    List<Survey.Pos> path;
    boolean needsRejoin;

    SeaRouteProgress(SeaPlanner.Plan plan) {
        this.plan=plan;
        path=done()?List.of():List.copyOf(plan.stops().get(0).path());
    }

    boolean done() { return stop>=plan.stops().size(); }

    int advanceCompleted(Survey survey) {
        int previous=stop;
        while(!done()&&plan.stops().get(stop).candidates().stream()
            .allMatch(id->survey.candidates.get(id-1).visited))stop++;
        if(stop!=previous) {
            path=List.of();waypoint=0;needsRejoin=!done();
        }
        return stop-previous;
    }

    record ScanState(int remaining,int loaded) {
        boolean done() { return remaining==0; }
        boolean scanHere() { return remaining>0&&loaded==remaining; }
    }

    ScanState scanState(Survey survey,Survey.View view) {
        int remaining=0,loaded=0;
        if(!done())for(int id:plan.stops().get(stop).candidates()) {
            var candidate=survey.candidates.get(id-1);
            if(!candidate.visited) {
                remaining++;
                if(survey.areaLoaded(candidate,view))loaded++;
            }
        }
        return new ScanState(remaining,loaded);
    }

    void prioritizeLoaded(Survey survey,Survey.View view) {
        if(done())return;
        var group=plan.stops().get(stop).candidates();
        int active=survey.activeCandidateId();
        if(group.contains(active)) {
            var candidate=survey.candidates.get(active-1);
            if(!candidate.visited&&survey.areaLoaded(candidate,view)) {
                survey.prioritize(candidate);return;
            }
        }
        for(int id:group) {
            var candidate=survey.candidates.get(id-1);
            if(!candidate.visited&&survey.areaLoaded(candidate,view)) {
                survey.prioritize(candidate);return;
            }
        }
    }

    Survey.Pos destination() {
        return done() || path.isEmpty() ? null : path.get(path.size() - 1);
    }

    void rejoined(List<Survey.Pos> path) {
        this.path=List.copyOf(path);waypoint=0;needsRejoin=false;
    }
}

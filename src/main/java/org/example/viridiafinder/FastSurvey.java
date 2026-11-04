package org.example.viridiafinder;

import java.util.*;
import java.util.function.Consumer;

/** 利用客户端已有位置索引优先发现；索引缺失不能成为排除箱子的依据。 */
public final class FastSurvey {
    public final Survey survey;
    private List<Survey.Candidate> order=List.of();
    private List<Survey.Pos> positions=List.of();
    private int candidateIndex,positionIndex,chunkX,chunkZ;
    private boolean started;
    private int radius=-1,below=-1,above=-1;
    private final Map<Long,List<Survey.Pos>> indices=new HashMap<>();
    private final Map<Survey.Pos,Survey.Kind> states=new HashMap<>();
    public long rounds;
    public FastSurvey(Survey survey){this.survey=survey;}
    private void reorder(Survey.Pos player){
        order=survey.candidates.stream().sorted(Comparator.comparingDouble(c->c.pos.distance(player))).toList();
        candidateIndex=0;started=false;positions=List.of();positionIndex=0;
        indices.clear();states.clear();
        radius=survey.settings.radius;below=survey.settings.below;above=survey.settings.above;
    }
    public int tick(Survey.View view,Survey.Pos player,int budget,long deadline,Consumer<Survey.Found> discovered){
        if(order.isEmpty()||radius!=survey.settings.radius||below!=survey.settings.below||above!=survey.settings.above)reorder(player);
        int spent=0;
        while(!order.isEmpty()&&spent<budget&&System.nanoTime()<deadline){
            var c=order.get(candidateIndex);
            if(!started){chunkX=(c.pos.x()-radius)>>4;chunkZ=(c.pos.z()-radius)>>4;started=true;}
            if(positionIndex<positions.size()){
                var p=positions.get(positionIndex++);spent++;
                if(p.y()>=view.bottom()&&p.y()<view.top()&&survey.inRange(c,p)&&view.loaded(p.x()>>4,p.z()>>4))
                    survey.observe(c,p,states.computeIfAbsent(p,view::read),discovered);
                continue;
            }
            if(chunkX>((c.pos.x()+radius)>>4)){
                started=false;
                if(++candidateIndex>=order.size()){rounds++;reorder(player);break;}
                continue;
            }
            int x=chunkX,z=chunkZ;
            if(++chunkZ>((c.pos.z()+radius)>>4)){chunkZ=(c.pos.z()-radius)>>4;chunkX++;}
            spent++;
            long key=((long)x<<32)^(z&0xffffffffL);
            positions=view.loaded(x,z)?indices.computeIfAbsent(key,k->view.indexedPositions(x,z)):List.of();
            positionIndex=0;
        }
        return spent;
    }
}

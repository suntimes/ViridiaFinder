package org.example.viridiafinder;

import java.io.*;
import java.util.*;

/** Historical surface-water navigation grid; never used as a container observation. */
public final class SeaMap {
    final int x0,z0,step,w,h; final byte[] cells;
    public SeaMap(int x0,int z0,int step,int w,int h,byte[] cells){this.x0=x0;this.z0=z0;this.step=step;this.w=w;this.h=h;this.cells=cells.clone();}
    public static SeaMap load() throws IOException {
        try(var stream=SeaMap.class.getResourceAsStream("/assets/viridiafinder/isles-water.bin")){
            if(stream==null)throw new IOException("海面数据缺失");
            var in=new DataInputStream(stream);
            if(in.readInt()!=0x56465331)throw new IOException("海面数据版本错误");
            int x=in.readInt(),z=in.readInt(),s=in.readInt(),w=in.readInt(),h=in.readInt();
            if(s!=4||w<=0||h<=0||(long)w*h>2_000_000)throw new IOException("海面数据尺寸异常");
            byte[] data=in.readNBytes(w*h);if(data.length!=w*h)throw new IOException("海面数据不完整");
            var result=new SeaMap(x,z,s,w,h,data);result.keepOcean();return result;
        }
    }
    public int cell(Survey.Pos p){int x=Math.floorDiv(p.x()-x0,step),z=Math.floorDiv(p.z()-z0,step);return x<0||x>=w||z<0||z>=h?-1:z*w+x;}
    public boolean water(int i){return i>=0&&i<cells.length&&cells[i]==2;}
    private void keepOcean(){
        int[] labels=new int[cells.length],queue=new int[cells.length];int label=0,best=0,maximum=0;
        for(int i=0;i<cells.length;i++)if(cells[i]==2&&labels[i]==0){
            label++;int head=0,tail=1;queue[0]=i;labels[i]=label;
            while(head<tail){int p=queue[head++];
                for(int next:new int[]{p%w>0?p-1:-1,p%w+1<w?p+1:-1,p>=w?p-w:-1,p+w<cells.length?p+w:-1})
                    if(next>=0&&cells[next]==2&&labels[next]==0){labels[next]=label;queue[tail++]=next;}
            }
            if(tail>maximum){maximum=tail;best=label;}
        }
        for(int i=0;i<cells.length;i++)if(cells[i]==2&&labels[i]!=best)cells[i]=1;
    }
    public Survey.Pos point(int i){return new Survey.Pos(x0+(i%w)*step+step/2,80,z0+(i/w)*step+step/2);}
    int oceanEntry(Survey.Pos start) {
        if (cell(start) < 0) return -1;
        int nearby = nearest(start, 256);
        if (nearby >= 0) return nearby;
        int mapDiagonal = (int)Math.ceil(step * Math.hypot(w, h));
        return nearest(start, mapDiagonal);
    }
    public int nearest(Survey.Pos p,int max){
        int center=cell(p);if(water(center))return center;
        int gx=Math.floorDiv(p.x()-x0,step),gz=Math.floorDiv(p.z()-z0,step),best=-1;double distance=Double.POSITIVE_INFINITY;
        int radius=max/step+1;
        for(int z=Math.max(0,gz-radius);z<Math.min(h,gz+radius+1);z++)for(int x=Math.max(0,gx-radius);x<Math.min(w,gx+radius+1);x++){
            int i=z*w+x;if(!water(i))continue;double d=RouteOptimizer.distance(p,point(i));
            if(d<=max&&d<distance){distance=d;best=i;}
        }return best;
    }
    public boolean clear(int a,int b){
        if(!water(a)||!water(b))return false;
        int ax=a%w,az=a/w,bx=b%w,bz=b/w,steps=Math.max(Math.abs(ax-bx),Math.abs(az-bz))*4;
        int previous=a;
        for(int i=1;i<=steps;i++){
            int x=(int)Math.round(ax+(bx-ax)*(double)i/steps),z=(int)Math.round(az+(bz-az)*(double)i/steps),at=z*w+x;
            if(!water(at))return false;
            if(x!=previous%w&&z!=previous/w&&(!water(z*w+previous%w)||!water((previous/w)*w+x)))return false;
            previous=at;
        }return true;
    }
    private record Node(int index,double cost,double score){}
    public List<Survey.Pos> path(int from,int to){
        if(!water(from)||!water(to))return List.of();
        if(clear(from,to))return from==to?List.of(point(from)):List.of(point(from),point(to));
        double[] costs=new double[cells.length];Arrays.fill(costs,Double.POSITIVE_INFINITY);
        int[] parent=new int[cells.length];Arrays.fill(parent,-1);
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score));
        costs[from]=0;queue.add(new Node(from,0,heuristic(from,to)));
        while(!queue.isEmpty()){
            var node=queue.remove();int at=node.index();if(node.cost()>costs[at])continue;
            if(at==to){
                List<Integer> raw=new ArrayList<>();for(int p=to;p!=-1;p=parent[p])raw.add(p);Collections.reverse(raw);
                List<Survey.Pos> result=new ArrayList<>();int i=0;result.add(point(raw.get(0)));
                while(i<raw.size()-1){int end=i+1;while(end+1<raw.size()&&clear(raw.get(i),raw.get(end+1)))end++;result.add(point(raw.get(end)));i=end;}
                return result;
            }
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                if(dx==0&&dz==0)continue;int x=at%w+dx,z=at/w+dz;
                if(x<0||x>=w||z<0||z>=h)continue;int next=z*w+x;if(!water(next))continue;
                if(dx!=0&&dz!=0&&(!water((at/w)*w+x)||!water(z*w+at%w)))continue;
                double cost=node.cost()+(dx==0||dz==0?step:step*Math.sqrt(2));
                if(cost<costs[next]){costs[next]=cost;parent[next]=at;queue.add(new Node(next,cost,cost+heuristic(next,to)));}
            }
        }return List.of();
    }
    private double heuristic(int a,int b){return Math.hypot(a%w-b%w,a/w-b/w)*step;}
}

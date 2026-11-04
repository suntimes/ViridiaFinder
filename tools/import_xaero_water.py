"""Read-only importer for the user's Xaero 1.39.12 region format 8/6.
Unknown format/data fails closed; no Minecraft files are changed.
Only surface terrain classification is exported, never containers.
"""
import struct, zipfile, pathlib, collections, json, zlib, sys

class Reader:
    def __init__(self,data): self.data=data; self.p=0; self.palette=[]
    def read(self,n):
        if n<0 or self.p+n>len(self.data): raise ValueError('truncated')
        value=self.data[self.p:self.p+n]; self.p+=n; return value
    def byte(self): return self.read(1)[0]
    def integer(self): return struct.unpack('>i',self.read(4))[0]
    def utf(self): return self.read(struct.unpack('>H',self.read(2))[0]).decode('utf8')
    def tag(self,t):
        if t in (1,2,3,4,5,6): return self.read({1:1,2:2,3:4,4:8,5:4,6:8}[t])
        if t==8:return self.utf()
        if t in (7,11,12):return self.read(self.integer()*{7:1,11:4,12:8}[t])
        if t==9:
            kind=self.byte();return [self.tag(kind) for _ in range(self.integer())]
        if t==10:
            value={}
            while (kind:=self.byte())!=0:
                name=self.utf();value[name]=self.tag(kind)
            return value
        raise ValueError('NBT type '+str(t))
    def state(self,new):
        if not new:return self.palette[self.integer()]
        assert self.byte()==10
        self.utf();value=self.tag(10).get('Name','unknown');self.palette.append(value);return value
    def pixel(self,flags):
        state=self.state(flags&2097152) if flags&1 else 'solid_default'
        h=self.byte() if flags&64 else ((flags>>12)&255)|(((flags>>25)&15)<<8)
        if h>=2048:h-=4096
        top=self.byte() if flags&16777216 else h
        # Saved overlays are accumulated above the base; any non-water overlay is
        # conservatively blocked (including foliage/glass), rather than guessed free.
        water=False;blocked=False
        if flags&2:
            for _ in range(self.byte()):
                overlay=self.integer()
                name=self.state(overlay&1024) if overlay&1 else 'minecraft:water'
                if overlay&4:self.integer()
                water |= name=='minecraft:water'
                blocked |= name!='minecraft:water'
        if flags&1048576:
            if flags&4194304:
                if flags&8388608:self.integer()
                else:self.utf()
            else:self.integer()
        return (water or state=='minecraft:water') and not blocked and 79<=top<=81,top

def main():
    source=pathlib.Path(sys.argv[1]);destination=pathlib.Path(sys.argv[2]);destination.mkdir(parents=True,exist_ok=True)
    x0,z0,step,w,h=-2560,-1024,4,896,800
    water=bytearray(w*h);known=bytearray(w*h);stats=collections.Counter();heights=collections.Counter()
    for path in sorted(source.glob('*.zip')):
        try: rx,rz=map(int,path.stem.split('_'))
        except ValueError: continue
        if not (-5<=rx<=1 and -2<=rz<=4):continue
        with zipfile.ZipFile(path) as archive:r=Reader(archive.read('region.xaero'))
        assert r.byte()==255 and r.integer()==0x00060008, 'unsupported region version: '+str(path)
        region=[]
        while r.p<len(r.data):
            group=r.byte();cx,cz=group>>4,group&15
            assert cx<8 and cz<8
            for tx in range(4):
                for tz in range(4):
                    flag=r.integer()
                    if flag==-1:continue
                    for bx in range(16):
                        for bz in range(16):
                            if bx or bz:flag=r.integer()
                            wet,top=r.pixel(flag)
                            x=rx*512+cx*64+tx*16+bx;z=rz*512+cz*64+tz*16+bz
                            gx,gz=(x-x0)//step,(z-z0)//step
                            if 0<=gx<w and 0<=gz<h:
                                index=gz*w+gx;known[index]+=1;water[index]+=wet
                                if wet:heights[top]+=1
                    r.byte();r.integer();r.byte() # interpretation version and cave metadata
        assert r.p==len(r.data)
        stats['regions']+=1
        print(path.stem,flush=True)
    grid=bytes(2 if k==16 and v==16 else 1 if k else 0 for k,v in zip(known,water))
    header=struct.pack('>6i',0x56465331,x0,z0,step,w,h)
    (destination/'isles-water.bin').write_bytes(header+grid)
    colors={0:(18,24,33),1:(111,101,77),2:(35,137,192)}
    raw=b''.join(b'\0'+b''.join(bytes(colors[v]) for v in grid[y*w:(y+1)*w]) for y in range(h))
    def chunk(tag,payload):return struct.pack('>I',len(payload))+tag+payload+struct.pack('>I',zlib.crc32(tag+payload)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
    (destination/'isles-water-preview.png').write_bytes(png)
    metadata=dict(source=str(source),format='Xaero region 8/6',origin=[x0,z0],step=step,width=w,height=h,regions=stats['regions'],cells=dict(collections.Counter(grid)),water_heights=dict(heights),policy='All 16 pixels must be known surface water at Y79..81; non-water overlays blocked; unknown blocked.')
    (destination/'water-source.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(metadata,ensure_ascii=False))
if __name__=='__main__':main()

"""Render a new coordinate chart from terrain-grid data and the tested sea-route CSV."""
import struct,zlib,csv,pathlib,sys
grid_path,csv_path,output=map(pathlib.Path,sys.argv[1:4])
data=grid_path.read_bytes();magic,x0,z0,step,w,h=struct.unpack('>6i',data[:24]);grid=data[24:]
assert magic==0x56465331 and len(grid)==w*h
palette={0:(19,25,34),1:(112,103,78),2:(30,110,158)}
pixels=bytearray(b''.join(bytes(palette[v]) for v in grid))
def dot(x,y,color,radius=1):
    for dy in range(-radius,radius+1):
        for dx in range(-radius,radius+1):
            if dx*dx+dy*dy<=radius*radius and 0<=x+dx<w and 0<=y+dy<h:
                at=((y+dy)*w+x+dx)*3;pixels[at:at+3]=bytes(color)
def line(a,b):
    n=max(abs(a[0]-b[0]),abs(a[1]-b[1]))
    for i in range(n+1):
        t=0 if not n else i/n;dot(round(a[0]+(b[0]-a[0])*t),round(a[1]+(b[1]-a[1])*t),(255,188,74),0)
rows=list(csv.DictReader(csv_path.open(encoding='utf8')))
previous=None;stations={}
for row in rows:
    p=((int(row['X'])-x0)//step,(int(row['Z'])-z0)//step)
    if previous is not None:line(previous,p)
    previous=p;stations[row['扫描站']]=p
for p in stations.values():dot(*p,(255,245,218),3)
if rows:dot((int(rows[0]['X'])-x0)//step,(int(rows[0]['Z'])-z0)//step,(82,245,162),5)
raw=b''.join(b'\0'+pixels[y*w*3:(y+1)*w*3] for y in range(h))
def chunk(tag,payload):return struct.pack('>I',len(payload))+tag+payload+struct.pack('>I',zlib.crc32(tag+payload)&0xffffffff)
output.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
print(len(stations),'stations',len(rows),'waypoints')

# Optional desktop check: python -m pip install moderngl numpy
from pathlib import Path
import math,moderngl,numpy as np
source = (Path(__file__).resolve().parents[1] / "app/src/main/java/com/okuyuki/quest/OsmoSphericalRenderer.kt").read_text(encoding="utf8")
import re
vertex = re.search(r'private const val VERTEX_SHADER = """(.*?)"""', source, re.S).group(1)
fragment = re.search(r'internal const val FRAGMENT_SHADER = """(.*?)"""', source, re.S).group(1)
vertex = '#version 330\n' + vertex.replace('attribute vec2 position;', 'in vec2 position;').replace('varying vec2 screen;', 'out vec2 screen;')
fragment = '\n'.join(x for x in fragment.splitlines() if '#extension' not in x and 'precision ' not in x)
fragment = '#version 330\nout vec4 fragColor;\n' + fragment.replace('varying vec2 screen;', 'in vec2 screen;').replace('samplerExternalOES','sampler2D').replace('texture2D(','texture(').replace('gl_FragColor','fragColor')
c = moderngl.create_standalone_context(require=330)
p = c.program(vertex_shader=vertex,fragment_shader=fragment)
b = c.buffer(np.array([-1,-1,1,-1,-1,1,1,1],dtype='f4').tobytes()); vao=c.simple_vertex_array(p,b,'position')
target=c.simple_framebuffer((360,180),components=3); target.use()
for proxy in (0,1):
  img=np.zeros((8,16,3),dtype='u1'); img[:,:8,0]=255; img[:,8:,1]=255
  if not proxy: img[:,:,0]=255; img[:,:,1]=0
  t=c.texture((16,8),3,img.tobytes()); t.use(0)
  green=np.zeros((8,16,3),dtype='u1'); green[:,:,1]=255
  t2=c.texture((16,8),3,green.tobytes()); t2.use(1)
  p['video0'].value=0; p['video1'].value=1; p['proxy'].value=proxy
  for i in (0,1): p['texMatrix'+str(i)].write(np.eye(4,dtype='f4').tobytes())
  for calibrated in (0,1):
    p['calibrated'].value=calibrated
    if calibrated:
      for side,sign in [('left',-1),('right',1)]:
        p[side+'Intrinsics'].value=(.27,.27,.5,.5);p[side+'Distortion'].value=(0.,0.,0.,0.);p[side+'K5'].value=0.;p[side+'Quaternion'].value=(2**-.5,sign*2**-.5,0.,0.)
    vao.render(mode=moderngl.TRIANGLE_STRIP)
    out=np.frombuffer(target.read(components=3,alignment=1),dtype=np.uint8).reshape(180,360,3)
    assert tuple(out[90,180])==(255,0,0),(proxy,calibrated,'front',out[90,180])
    assert tuple(out[90,0])==(0,255,0),(proxy,calibrated,'back',out[90,0])
print('sphere shaders: proxy and two-track front/back, calibrated and fallback: OK')

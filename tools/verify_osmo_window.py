# Optional desktop check: python -m pip install moderngl numpy
from pathlib import Path
import re
import moderngl
import numpy as np

source = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/okuyuki/quest/DualTrackFisheyeView.kt').read_text(encoding='utf8')
vertex = re.search(r'private const val VERTEX_SHADER = """(.*?)"""', source, re.S).group(1)
fragment = re.search(r'private const val FRAGMENT_SHADER = """(.*?)"""', source, re.S).group(1)
vertex = '#version 330\n' + vertex.replace('attribute vec2 position;', 'in vec2 position;').replace('varying vec2 screen;', 'out vec2 screen;')
fragment = '\n'.join(line for line in fragment.splitlines() if '#extension' not in line and 'precision ' not in line)
fragment = ('#version 330\nout vec4 fragColor;\n' + fragment.replace('varying vec2 screen;', 'in vec2 screen;')
    .replace('samplerExternalOES', 'sampler2D').replace('texture2D(', 'texture(').replace('gl_FragColor', 'fragColor'))
context = moderngl.create_standalone_context(require=330)
program = context.program(vertex_shader=vertex, fragment_shader=fragment)
buffer = context.buffer(np.array([-1,-1,1,-1,-1,1,1,1], dtype='f4').tobytes())
quad = context.simple_vertex_array(program, buffer, 'position')
target = context.simple_framebuffer((64, 64), components=3)
target.use()
for index, color in enumerate(((255,0,0), (0,255,0))):
    pixels = np.full((8, 8, 3), color, dtype='u1')
    texture = context.texture((8, 8), 3, pixels.tobytes())
    texture.use(index)
    program[f'video{index}'].value = index
    program[f'texMatrix{index}'].write(np.eye(4, dtype='f4').tobytes())
program['aspect'].value = 1.0
program['yaw'].value = 0.0
program['pitch'].value = 0.0
for calibrated in (0, 1):
    program['calibrated'].value = calibrated
    if calibrated:
        for side, sign in (('left', -1), ('right', 1)):
            program[side + 'Intrinsics'].value = (.27,.27,.5,.5)
            program[side + 'Distortion'].value = (0.,0.,0.,0.)
            program[side + 'K5'].value = 0.
            program[side + 'Quaternion'].value = (2**-.5,sign*2**-.5,0.,0.)
    for correction, expected in [((1.,0.,0.,0.), (255,0,0)), ((0.,0.,0.,1.), (0,255,0))]:
        program['motionCorrection'].value = correction
        quad.render(mode=moderngl.TRIANGLE_STRIP)
        pixels = np.frombuffer(target.read(components=3, alignment=1), dtype=np.uint8).reshape(64,64,3)
        assert tuple(pixels[32,32]) == expected, (calibrated, correction, pixels[32,32])
print('OSV window shader: calibrated and uncalibrated identity and half-turn correction OK')

"""Check guest Mesa virpipe with offscreen GLES draw/readback, without GLX probing."""
import ctypes as C
import json


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn


def check(condition, detail):
    if not condition:
        raise RuntimeError(str(detail))


def probe():
    egl, gl = C.CDLL('libEGL.so.1'), C.CDLL('libGLESv2.so.2')
    p, i, u, f = C.c_void_p, C.c_int, C.c_uint, C.c_float
    display = bind(egl, 'eglGetDisplay', p, p)(None)
    context, surface = None, None
    try:
        major, minor = i(), i()
        check(bind(egl, 'eglInitialize', u, p, p, p)(display, C.byref(major), C.byref(minor)), 'eglInitialize')
        attrs = (i * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = p(), i()
        check(bind(egl, 'eglChooseConfig', u, p, p, p, i, p)(display, attrs, C.byref(config), 1, C.byref(count)) and count.value, 'RGBA config')
        check(bind(egl, 'eglBindAPI', u, u)(0x30A0), 'GLES API')
        ctx_attrs = (i * 3)(0x3098, 2, 0x3038)
        context = bind(egl, 'eglCreateContext', p, p, p, p, p)(display, config, None, ctx_attrs)
        size = (i * 5)(0x3057, 64, 0x3056, 64, 0x3038)
        surface = bind(egl, 'eglCreatePbufferSurface', p, p, p, p)(display, config, size)
        check(context and surface, 'GLES context/pbuffer')
        check(bind(egl, 'eglMakeCurrent', u, p, p, p, p)(display, surface, surface, context), 'make current')
        string = bind(gl, 'glGetString', C.c_char_p, u)
        report = {name: string(key).decode() for name, key in
                  [('vendor', 0x1F00), ('renderer', 0x1F01), ('version', 0x1F02)]}
        check(report['renderer'].startswith('virgl ('), report)
        clear_color = bind(gl, 'glClearColor', None, f, f, f, f)
        clear = bind(gl, 'glClear', None, u)
        clear_color(0, 0, 1, 1)
        clear(0x4000)
        bind(gl, 'glEnable', None, u)(0x0C11)
        bind(gl, 'glScissor', None, i, i, i, i)(16, 16, 32, 32)
        clear_color(1, 0, 0, 1)
        clear(0x4000)
        bind(gl, 'glFinish', None)()
        read = bind(gl, 'glReadPixels', None, i, i, i, i, u, u, p)
        center, corner = (C.c_ubyte * 4)(), (C.c_ubyte * 4)()
        read(32, 32, 1, 1, 0x1908, 0x1401, center)
        read(0, 0, 1, 1, 0x1908, 0x1401, corner)
        check(bind(gl, 'glGetError', u)() == 0, 'GL draw/read error')
        report.update(center=list(center), corner=list(corner))
        check(center[0] > 240 and center[1] < 10 and center[2] < 10, report)
        check(corner[0] < 10 and corner[1] < 10 and corner[2] > 240, report)
        report['verified'] = True
        return report
    finally:
        bind(egl, 'eglMakeCurrent', u, p, p, p, p)(display, None, None, None)
        if surface:
            bind(egl, 'eglDestroySurface', u, p, p)(display, surface)
        if context:
            bind(egl, 'eglDestroyContext', u, p, p)(display, context)
        bind(egl, 'eglTerminate', u, p)(display)


if __name__ == '__main__':
    print(json.dumps(probe()))

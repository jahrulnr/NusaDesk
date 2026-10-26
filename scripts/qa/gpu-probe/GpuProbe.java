package qa.nusadesk.gpuprobe;

import android.app.Instrumentation;
import android.opengl.*;
import android.os.Bundle;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class GpuProbe extends Instrumentation {
    private static native String vulkan();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        JSONObject report = new JSONObject();
        try {
            report.put("uid", android.os.Process.myUid());
            report.put("selinux", new String(Files.readAllBytes(Paths.get("/proc/self/attr/current"))).trim());
            try { report.put("egl", probeEgl()); }
            catch (Throwable error) { report.put("eglError", android.util.Log.getStackTraceString(error)); }
            System.loadLibrary("gpuprobe");
            report.put("vulkan", vulkan());
            result.putString("report", report.toString(2));
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
    private JSONObject probeEgl() throws Exception {
        EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext c = EGL14.EGL_NO_CONTEXT;
        EGLSurface s = EGL14.EGL_NO_SURFACE;
        try {
            int[] version = new int[2];
            check(EGL14.eglInitialize(d, version, 0, version, 1), "eglInitialize");
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            check(EGL14.eglChooseConfig(d, new int[]{EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,count,0)
                && count[0] > 0, "eglChooseConfig ES3 pbuffer");
            c = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
            s = EGL14.eglCreatePbufferSurface(d,configs[0],
                new int[]{EGL14.EGL_WIDTH,64,EGL14.EGL_HEIGHT,64,EGL14.EGL_NONE},0);
            check(EGL14.eglMakeCurrent(d,s,s,c), "eglMakeCurrent");
            JSONObject r = new JSONObject();
            r.put("vendor",GLES30.glGetString(GLES30.GL_VENDOR));
            r.put("renderer",GLES30.glGetString(GLES30.GL_RENDERER));
            r.put("version",GLES30.glGetString(GLES30.GL_VERSION));
            r.put("eglExtensions",EGL14.eglQueryString(d,EGL14.EGL_EXTENSIONS));
            int vs=shader(GLES30.GL_VERTEX_SHADER,"#version 300 es\nconst vec2 p[3]=vec2[3](vec2(-1.,-1.),vec2(1.,-1.),vec2(0.,1.));void main(){gl_Position=vec4(p[gl_VertexID],0.,1.);}");
            int fs=shader(GLES30.GL_FRAGMENT_SHADER,"#version 300 es\nprecision mediump float;out vec4 color;void main(){color=vec4(1.,0.,0.,1.);}");
            int program=GLES30.glCreateProgram(); GLES30.glAttachShader(program,vs); GLES30.glAttachShader(program,fs);
            GLES30.glLinkProgram(program); int[] linked=new int[1]; GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,linked,0);
            check(linked[0]!=0, "link: "+GLES30.glGetProgramInfoLog(program));
            GLES30.glViewport(0,0,64,64); GLES30.glClearColor(0,0,1,1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glUseProgram(program); GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3); GLES30.glFinish();
            ByteBuffer pixel=ByteBuffer.allocateDirect(4); GLES30.glReadPixels(32,32,1,1,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixel);
            check(GLES30.glGetError()==GLES30.GL_NO_ERROR,"GL draw/read error");
            check((pixel.get(0)&255)>240 && (pixel.get(1)&255)<10 && (pixel.get(2)&255)<10,"red triangle pixel");
            r.put("trianglePixelVerified",true);
            GLES30.glDeleteProgram(program); GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs);
            return r;
        } finally {
            EGL14.eglMakeCurrent(d,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(s!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d,s);
            if(c!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(d,c);
            EGL14.eglTerminate(d);
        }
    }
    private int shader(int type,String source) {
        int shader=GLES30.glCreateShader(type); GLES30.glShaderSource(shader,source); GLES30.glCompileShader(shader);
        int[] compiled=new int[1]; GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,compiled,0);
        check(compiled[0]!=0,"compile: "+GLES30.glGetShaderInfoLog(shader)); return shader;
    }
    private static void check(boolean ok,String operation) { if(!ok) throw new AssertionError(operation); }
}

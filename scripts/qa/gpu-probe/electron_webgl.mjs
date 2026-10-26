// QA only: Electron must already expose its temporary loopback DevTools port.
// Node 22+ supplies fetch/WebSocket; this opens no listener of its own.
const port = process.argv[2] || '19224';
const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const target = targets.find(item => item.type === 'page');
if (!target) throw new Error('No Electron page target');

function drawTriangle(antialias) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  const gl = canvas.getContext('webgl', {preserveDrawingBuffer: true, antialias});
  if (!gl) throw new Error('WebGL context unavailable');
  const compile = (type, source) => {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
    return shader;
  };
  const vs = compile(gl.VERTEX_SHADER, 'attribute vec2 p; void main(){gl_Position=vec4(p,0.,1.);}');
  const fs = compile(gl.FRAGMENT_SHADER, 'precision mediump float; void main(){gl_FragColor=vec4(1.,0.,0.,1.);}');
  const program = gl.createProgram();
  gl.attachShader(program, vs); gl.attachShader(program, fs); gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  gl.useProgram(program);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,0,1]), gl.STATIC_DRAW);
  const position = gl.getAttribLocation(program, 'p');
  gl.enableVertexAttribArray(position); gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
  gl.viewport(0, 0, 64, 64); gl.clearColor(0, 0, 1, 1); gl.clear(gl.COLOR_BUFFER_BIT);
  gl.drawArrays(gl.TRIANGLES, 0, 3); gl.finish();
  const center = new Uint8Array(4), corner = new Uint8Array(4);
  gl.readPixels(32, 32, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, center);
  gl.readPixels(0, 63, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, corner);
  const error = gl.getError();
  if (error !== gl.NO_ERROR || center[0] < 240 || center[1] > 10 || center[2] > 10 ||
      corner[0] > 10 || corner[1] > 10 || corner[2] < 240) throw new Error(
        JSON.stringify({error, center: [...center], corner: [...corner]}));
  const debug = gl.getExtension('WEBGL_debug_renderer_info');
  const renderer = debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
  gl.deleteBuffer(buffer); gl.deleteProgram(program); gl.deleteShader(vs); gl.deleteShader(fs);
  const actualAntialias = gl.getContextAttributes()?.antialias;
  gl.getExtension('WEBGL_lose_context')?.loseContext();
  return {renderer, requestedAntialias: antialias, actualAntialias,
    center: [...center], corner: [...corner], trianglePixelVerified: true};
}

const result = await new Promise((resolve, reject) => {
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  const timer = setTimeout(() => {ws.close(); reject(new Error('WebGL probe timeout'));}, 20000);
  ws.onopen = () => ws.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {
    expression: `(${drawTriangle.toString()})(${process.argv[3] === 'true'})`, returnByValue: true,
  }}));
  ws.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id !== 1) return;
    clearTimeout(timer); ws.close();
    if (message.error || message.result.exceptionDetails) reject(new Error(JSON.stringify(message)));
    else resolve(message.result.result.value);
  };
  ws.onerror = reject;
});
console.log(JSON.stringify(result, null, 2));

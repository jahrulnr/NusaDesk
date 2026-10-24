'use strict';
const assert = require('assert');
const {install} = require('../../main/assets/terminal/touch-scroll.js');
let tracking = true, selection = null;
const listeners = {}, wheels = [], frames = [];
const viewport = {clientHeight: 400};
const root = {classList: {contains: () => tracking}};
const screen = {style: {}, dispatchEvent: e => { wheels.push(e); return false; }};
const container = {
  ownerDocument: { getSelection: () => selection, defaultView: {
    WheelEvent: class { constructor(type, props) { this.type = type; Object.assign(this, props); } },
    requestAnimationFrame: cb => {frames.push(cb); return frames.length;}, cancelAnimationFrame: () => {}
  }},
  querySelector: selector => ({'.xterm': root, '.xterm-screen': screen, '.xterm-viewport': viewport,
    '.xterm-rows': {contains: node => node === 'selected'}}[selector]),
  addEventListener: (name, cb) => { listeners[name] = cb; },
  removeEventListener: name => { delete listeners[name]; }
};
global.document = {querySelector: selector => container.querySelector(selector)};
const term = {rows: 20, buffer: {active: {type: 'alternate', viewportY: 0, length: 20}}};
const dispose = install(container, term);
let prevented = 0;
const event = (y, count=1) => ({touches: Array.from({length:count}, (_,i)=>({identifier:i+1,clientX:120,clientY:y})),
  preventDefault: ()=>{prevented++;}, stopPropagation: ()=>{}});
function begin(y=200) { listeners.touchstart(event(y)); }
function move(y, count=1) { listeners.touchmove(event(y,count)); }
function end() { listeners.touchend({touches:[]}); }
begin(); move(196); end();
assert.strictEqual(wheels.length,0,'tap/subthreshold movement must not send mouse input');
assert.strictEqual(prevented,0,'potential taps remain available for focus and long press');
begin(); move(209.5); end();
assert.strictEqual(prevented,1,'claim the first native slop-crossing move before browser pan/click ownership');
assert.strictEqual(wheels.length,0,'claiming the gesture does not round a sub-row movement into a wheel');
begin(); move(240); end();
assert.strictEqual(wheels.length,2,'40px drag must send two row-sized wheel events even without scrollback');
assert.ok(wheels.every(e=>e.type==='wheel' && e.deltaMode===1 && e.deltaY===-1));
assert.ok(wheels.every(e=>e.clientX===120 && e.clientY===200),'wheel stays in the panel where the gesture began');
assert.strictEqual(frames.length,0,'application scrolling must not start a scrollback fling');
wheels.length=0;
begin(); move(160); move(200); end();
assert.deepStrictEqual(wheels.map(e=>e.deltaY),[1,1,-1,-1],'reverse direction follows the finger');
wheels.length=0;
begin(); move(160,2); move(120); end();
assert.strictEqual(wheels.length,0,'a second finger cancels until all touches are released');
begin(); selection={isCollapsed:false,anchorNode:'selected',focusNode:'selected'}; move(240); end();
assert.strictEqual(wheels.length,0,'text selection retains the gesture'); selection=null;
begin(); move(211); move(221); move(231); end();
assert.strictEqual(wheels.length,1,'sub-row deltas accumulate without losing or duplicating rows');
wheels.length=0;
tracking=false; begin(); move(240); end();
assert.strictEqual(wheels.length,2,'alternate-screen apps without tracking use xterm wheel fallback');
wheels.length=0;
tracking=true; begin(); term.buffer.active.type='normal'; tracking=false; move(240); end();
assert.strictEqual(wheels.length,0,'leaving the TUI cancels an in-flight application gesture');
dispose();
assert.deepStrictEqual(Object.keys(listeners),[]);
console.log('TUI touch wheel routing: passed');

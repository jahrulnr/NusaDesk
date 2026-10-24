'use strict';
const assert=require('assert');
const {install}=require('../../main/assets/terminal/touch-scroll.js');
function target(){const map={};return {map,addEventListener:(n,f)=>{(map[n]||=new Set()).add(f)},removeEventListener:(n,f)=>map[n]?.delete(f),emit(n,e){for(const f of [...(map[n]||[])])f(e)},count(){return Object.values(map).reduce((n,s)=>n+s.size,0)}};}
const container=target(), hit=target(), second=target(), wheels=[];
let mouseTracking=true;
const screen={style:{},dispatchEvent:e=>{wheels.push(e.deltaY);return false}};
container.ownerDocument={getSelection:()=>null,defaultView:{WheelEvent:class{constructor(t,p){Object.assign(this,p)}}}};
container.querySelector=s=>s==='.xterm-viewport'?{clientHeight:400}:s==='.xterm-screen'?screen:{classList:{contains:()=>mouseTracking}};
const term={rows:20,buffer:{active:{type:'alternate'}}};
const dispose=install(container,term);
const event=(y,target=hit,count=1)=>({target,touches:Array.from({length:count},(_,i)=>({identifier:i+1,clientX:120,clientY:y})),preventDefault(){},stopPropagation(){}});
container.emit('touchstart',event(200));
assert.ok(hit.count()>=3,'retain move/end/cancel listeners on the original touch target');
let move=event(240);
container.emit('touchmove',move);hit.emit('touchmove',move);
assert.deepStrictEqual(wheels,[-1,-1],'bubbling must not deliver the same move twice');
// Renderer replaces this text node. Subsequent native touch events retain
// their original target and no longer bubble through the terminal container.
hit.isConnected=false;
for(let i=1;i<=120;i++)hit.emit('touchmove',event(240-i));
for(let i=1;i<=120;i++)hit.emit('touchmove',event(120+i));
assert.ok(wheels.includes(1)&&wheels.lastIndexOf(-1)>wheels.indexOf(1),'one retained gesture reverses direction after repaint');
hit.emit('touchend',{target:hit,touches:[]});
assert.strictEqual(hit.count(),0,'finish releases detached-node listeners');
container.emit('touchstart',event(200));
container.emit('touchstart',event(200,second,2));
hit.emit('touchend',{target:hit,touches:[{identifier:2}]});
assert.ok(second.count()>0,'remaining finger still has a termination listener');
second.emit('touchend',{target:second,touches:[]});
assert.strictEqual(hit.count()+second.count(),0,'last finger releases every retained target');
container.emit('touchstart',event(200));hit.emit('touchcancel',{target:hit,touches:[]});
assert.strictEqual(hit.count(),0,'cancel releases the original target');
container.emit('touchstart',event(200));dispose();
assert.strictEqual(hit.count()+container.count(),0,'disposing mid-gesture releases all listeners');
mouseTracking=false;
global.document={querySelector:s=>container.querySelector(s)};
let position=1000;
const normal={rows:20,buffer:{active:{type:'normal',viewportY:50,length:1000}},
 _core:{_viewport:{_scrollableElement:{getScrollPosition:()=>({scrollTop:position}),
 getScrollDimensions:()=>({height:400,scrollHeight:20000}),setScrollPosition:p=>{position=p.scrollTop}}}}};
const disposeNormal=install(container,normal);
hit.isConnected=true;container.emit('touchstart',event(200));
container.emit('touchmove',event(240));hit.isConnected=false;
hit.emit('touchmove',event(210));
assert.strictEqual(position,990,'normal scrollback reverses after its touched row is replaced');
hit.emit('touchmove',event(210.5));
assert.strictEqual(position,989.5,'detached-target scrolling retains fractional pixels');
hit.emit('touchend',{target:hit,touches:[]});disposeNormal();
assert.strictEqual(hit.count()+container.count(),0);
console.log('Touch target lifetime across DOM repaint: passed');

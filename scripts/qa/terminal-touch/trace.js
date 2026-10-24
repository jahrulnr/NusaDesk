/* Paste into the owned terminal's DevTools console, then call start().
 * An optional xterm instance enables fractional scroll-position sampling.
 * This observer does not cancel, synthesize or alter input events. */
(() => {
  if (window.NusaDeskTouchQa) window.NusaDeskTouchQa.dispose();
  let data, start, frame = 0, active = false, term = null, lastY = 0;
  const targets = new Set(), seen = new WeakSet();
  function release() {
    for (const node of targets) for (const type of ['touchmove','touchend','touchcancel']) node.removeEventListener(type, record, true);
    targets.clear();
  }
  function record(e) {
    if (seen.has(e)) return;
    seen.add(e);
    lastY = e.touches?.[0]?.clientY ?? lastY;
    const item = {t: Math.round(performance.now()-start), type:e.type,
      y:lastY, connected:e.target.isConnected, cancelable:e.cancelable, dy:e.deltaY};
    data.events.push(item);
    queueMicrotask(() => {item.prevented=e.defaultPrevented});
    if ((e.type==='touchend' && e.touches.length===0) || e.type==='touchcancel') {active=false;release();}
  }
  function sample() {
    const rows=document.querySelector('.xterm-rows');
    const scroll=term?._core?._viewport?._scrollableElement;
    data.frames.push({t:Math.round(performance.now()-start),y:lastY,
      pos:scroll?.getScrollPosition().scrollTop,
      head:rows?[...rows.children].slice(1,4).map(n=>n.textContent).join('|'):'',
      transform:document.querySelector('.xterm-screen')?.style.transform});
    if(active) frame=requestAnimationFrame(sample);
  }
  function begin(e) {
    record(e);
    const node=e.target;
    if(!targets.has(node)) {
      targets.add(node);
      for(const type of ['touchmove','touchend','touchcancel']) node.addEventListener(type,record,{capture:true,passive:true});
    }
    if(!active) {active=true;frame=requestAnimationFrame(sample);}
  }
  function dispose() {
    active=false;cancelAnimationFrame(frame);release();
    document.removeEventListener('touchstart',begin,true);
    for(const type of ['touchmove','touchend','touchcancel','wheel']) document.removeEventListener(type,record,true);
  }
  window.NusaDeskTouchQa={
    start(xterm=null) {
      dispose();term=xterm;data={events:[],frames:[]};start=performance.now();
      document.addEventListener('touchstart',begin,{capture:true,passive:true});
      for(const type of ['touchmove','touchend','touchcancel','wheel']) document.addEventListener(type,record,{capture:true,passive:true});
    },
    take(){return data;},
    dispose
  };
})();

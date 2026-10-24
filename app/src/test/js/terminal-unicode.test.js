'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');
const base = path.join(__dirname,'../../main/assets/terminal');
const {Terminal} = require(path.join(base,'xterm.js'));
const addon = require(path.join(base,'addon-unicode-graphemes.js'));
const html = fs.readFileSync(path.join(base,'terminal.html'),'utf8');
const boot = html.match(/term\.loadAddon\(new window\.UnicodeGraphemesAddon\.UnicodeGraphemesAddon\(\)\);/);
assert.ok(boot,'the production page must activate Unicode graphemes before opening the terminal');
assert.ok(html.indexOf(boot[0]) < html.indexOf('term.open(container)'));
assert.strictEqual(crypto.createHash('sha256').update(fs.readFileSync(path.join(base,'addon-unicode-graphemes.js'))).digest('hex'),
  '8e7f926f4def5733a190b80cff5cbaa550404e143b0750962f7d756138203cb8','pinned upstream addon bytes');
function terminal(cols=60) {
 const term = new Terminal({allowProposedApi:true,cols,rows:4});
 vm.runInNewContext(boot[0],{term,window:{UnicodeGraphemesAddon:addon}});
 return term;
}
const write = (term,s) => new Promise(resolve=>term.write(s,resolve));
(async()=>{
 const term=terminal();
 try {
  assert.strictEqual(term.unicode.activeVersion,'15-graphemes');
  const pieces=['A','😄','B','✅','C','⚠️','D','👩‍💻','E','👍🏽','F','🇮🇩','G','e\u0301','H','漢','I','─'];
  await write(term,pieces.join(''));
  const row=term.buffer.active.getLine(0);
  let col=0;
  for (const chars of pieces) {
   const width=/^(😄|✅|⚠️|👩‍💻|👍🏽|🇮🇩|漢)$/u.test(chars)?2:1;
   assert.strictEqual(row.getCell(col).getChars(),chars,'cell content '+col);
   assert.strictEqual(row.getCell(col).getWidth(),width,'width of '+chars);
   if(width===2) assert.strictEqual(row.getCell(col+1).getWidth(),0,'wide-glyph continuation');
   col+=width;
  }
  term.reset();
  await write(term,'⚠'); await write(term,'\uFE0F'); await write(term,'Z');
  assert.strictEqual(term.buffer.active.getLine(0).getCell(0).getChars(),'⚠️');
  assert.strictEqual(term.buffer.active.getLine(0).getCell(0).getWidth(),2);
  assert.strictEqual(term.buffer.active.getLine(0).getCell(2).getChars(),'Z');
 } finally {term.dispose();}
 const edge=terminal(8);
 try {
  await write(edge,'1234567😄Z');
  assert.strictEqual(edge.buffer.active.getLine(0).translateToString(true),'1234567');
  assert.strictEqual(edge.buffer.active.getLine(1).getCell(0).getChars(),'😄');
  assert.strictEqual(edge.buffer.active.getLine(1).getCell(0).getWidth(),2);
  assert.strictEqual(edge.buffer.active.getLine(1).getCell(2).getChars(),'Z');
 } finally {edge.dispose();}
 console.log('Terminal Unicode grapheme cells: passed');
})().catch(e=>{console.error(e);process.exitCode=1;});

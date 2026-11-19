/**
 * Smoke test upstream complete decoder independently of Android WebView.
 * Run after: bash scripts/install-wmbusmeters-wasm.sh
 *   node --experimental-default-type=module tests/wmbusmeters-wasm-smoke.mjs
 * Uses a public unencrypted sample from wmbusmeters README.
 */
import fs from 'node:fs/promises';
import path from 'node:path';

const base='app/src/main/assets/wmbus';
const wasm=await fs.readFile(path.join(base,'wmbusmeters.wasm'));
const js=await fs.readFile(path.join(base,'wmbusmeters.js'),'utf8');
const {default:create}=await import('data:text/javascript;base64,'+
                                    Buffer.from(js).toString('base64'));
const compiled=await WebAssembly.compile(wasm);
const stdout=[],stderr=[];
const mod=await create({
  print:line=>stdout.push(String(line)),
  printErr:line=>stderr.push(String(line)),
  instantiateWasm:(imports,success)=>{
    WebAssembly.instantiate(compiled,imports).then(success);
    return {};
  }
});
const frame='1844AE4C4455223368077A55000000041389E20100023B0000';
mod.ccall('wm_main','number',['string'],
          ['--format=json '+frame+' scan auto * NOKEY']);
const reading=stdout.find(line=>line.trimStart().startsWith('{'));
if(!reading) {
  console.error('WASM output:',stdout.slice(0,15));
  console.error('WASM stderr:',stderr.slice(0,15));
  throw new Error('wmbusmeters WASM did not return JSON');
}
const data=JSON.parse(reading);
if(!data.id || !data.meter)throw new Error('Missing decoded meter metadata');
console.log('wmbusmeters WASM smoke OK:',data.meter,data.id);

/*
 * Local-only Android WebView interface to the GPL wmbusmeters WASM engine.
 * Source: https://github.com/wmbusmeters/wmbusmeters, browser/bindings.cc
 * Prebuilt upstream derivative:
 * https://github.com/jasiek/web-rtl-wmbus (pinned in CI).
 * No network requests beyond the local appassets resource origin.
 * Actual source and build instructions: THIRD_PARTY_WMBUSMETERS.md
 */
import createWmbusmeters from './wmbusmeters.js';

let compiled;
let chain=Promise.resolve();
let backlog=0;
const MAX_QUEUE=24;

function notifyError(message) {
    try { window.AndroidWmbus?.onError(String(message).slice(0,400)); }
    catch (_) {}
}
(async ()=>{
    try {
        const response=await fetch('./wmbusmeters.wasm');
        if(!response.ok) throw new Error('WASM fetch HTTP '+response.status);
        compiled=await WebAssembly.compile(await response.arrayBuffer());
        window.AndroidWmbus?.onReady('wmbusmeters WASM loaded');
    } catch(e) { notifyError(e.message || String(e)); }
})();

/** Every decoder attempt creates a fresh wmbusmeters instance to avoid
 * cached meter failure blacklisting after a bad key or corrupted telegram. */
async function decodeOne(id,hex) {
    if(!compiled) {
        window.AndroidWmbus?.onResult(String(id),JSON.stringify({
            status:'unavailable',error:'WASM has not loaded'}));
        return;
    }
    const stdout=[],stderr=[];
    const app=await createWmbusmeters({
        print:line=>stdout.push(String(line)),
        printErr:line=>stderr.push(String(line)),
        instantiateWasm:(imports,success)=>{
            WebAssembly.instantiate(compiled,imports)
                .then(instance=>success(instance))
                .catch(err=>notifyError('WASM instantiate: '+err.message));
            return {};
        }
    });
    // Input came from CRC-verified CUL packets, not arbitrary HTML.
    // No decryption key is supplied unless the user explicitly manages one.
    // wmbusmeters recognises all drivers compiled into this upstream engine.
    app.ccall('wm_main','number',['string'],
              ['--format=json '+hex+' scan auto * NOKEY']);
    let reading=null;
    for(const line of stdout) {
        const value=line.trim();
        if(!value.startsWith('{'))continue;
        try {reading=JSON.parse(value);break;}catch(_){}
    }
    const meta=new Set(['_','media','meter','name','id','timestamp']);
    const hasValues=reading && Object.keys(reading).some(k=>!meta.has(k));
    window.AndroidWmbus?.onResult(String(id),JSON.stringify({
        status:hasValues?'decoded':reading?'recognized':'undecoded',
        decoder:'wmbusmeters-wasm',
        reading,
        diagnostics:stderr.slice(0,8).join('\n').slice(0,500),
    }));
}

/** Exposed only to this in-app WebView via JavaScript injection.
 * Sequential promises avoid allocating many concurrent 6MB instances. */
window.wmbusDecode = function(id,hex) {
    if(typeof hex!=='string' || !/^[0-9a-fA-F]{24,600}$/.test(hex) ||
       hex.length%2!==0 || backlog>=MAX_QUEUE)return false;
    backlog++;
    chain=chain.then(()=>decodeOne(id,hex))
        .catch(e=>window.AndroidWmbus?.onResult(String(id),JSON.stringify({
            status:'error',error:String(e).slice(0,300)
        })))
        .finally(()=>{backlog--;});
    return true;
};

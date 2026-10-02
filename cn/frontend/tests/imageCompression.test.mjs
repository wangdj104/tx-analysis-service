import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
function setup(overrides={}){
 const images=[],created=[],revoked=[],canvases=[]
 class File { constructor(parts,name,options){this.name=name;this.type=options.type;this.size=parts.reduce((n,p)=>n+(p.size||0),0)} }
 class Image { constructor(){this.naturalWidth=3840;this.naturalHeight=2160;images.push(this)} }
 const bindings={Image,File,URL:{createObjectURL(file){created.push(file);return'blob:synthetic'},revokeObjectURL:url=>revoked.push(url)},
  document:{createElement(){const canvas={width:0,height:0,getContext(){return{drawImage(){}}},toBlob(callback){callback({size:1000})}};canvases.push(canvas);return canvas}},...overrides}
 const source=fs.readFileSync(new URL('../src/utils/imageCompress.js',import.meta.url),'utf8').replace(/export /g,'')
 const api=new Function(...Object.keys(bindings),source+'\nreturn {compressImageFile,prepareUploadFiles,isImageFile}')(...Object.values(bindings))
 return {...api,images,created,revoked,canvases}
}
const photo={name:'fictional-report.png',type:'image/png',size:3*1024*1024}
for (const stage of ['canvas creation','context access','drawImage','toBlob']) {
 test(`compression falls back and settles when ${stage} throws`,async()=>{
  const v = setup({
    document: {
      createElement() {
        if (stage === 'canvas creation') throw Error(stage)
        return {
          getContext() {
            if (stage === 'context access') throw Error(stage)
            return { drawImage() { if (stage === 'drawImage') throw Error(stage) } }
          },
          toBlob() { throw Error(stage) }
        }
      }
    }
  })
  const result=v.compressImageFile(photo)
  assert.doesNotThrow(()=>v.images[0].onload())
  assert.strictEqual(await result,photo);assert.deepEqual(v.revoked,['blob:synthetic'])
 })
}
test('a failed Image constructor revokes the allocated URL and returns the original file',async()=>{
 const v=setup({Image:class{constructor(){throw Error('Synthetic decoder allocation failure')}}})
 assert.strictEqual(await v.compressImageFile(photo),photo);assert.deepEqual(v.revoked,['blob:synthetic'])
})
test('a failed source assignment revokes the URL and returns the original file',async()=>{
 const v=setup({Image:class{set src(_value){throw Error('Synthetic decoder source failure')}}})
 assert.strictEqual(await v.compressImageFile(photo),photo);assert.deepEqual(v.revoked,['blob:synthetic'])
})
test('image decoding failure returns the original and releases the URL',async()=>{
 const v=setup(),result=v.compressImageFile(photo);v.images[0].onerror();assert.strictEqual(await result,photo);assert.deepEqual(v.revoked,['blob:synthetic'])
})
test('successful compression preserves aspect ratio and avoids enlargement',async()=>{
 const v=setup(),result=v.compressImageFile(photo);v.images[0].onload();const out=await result
 assert.equal(out.name,'fictional-report.jpg');assert.equal(out.type,'image/jpeg');assert.equal(out.size,1000)
 assert.equal(v.canvases[0].width,1920);assert.equal(v.canvases[0].height,1080);assert.deepEqual(v.revoked,['blob:synthetic'])
})
test('small JPEG and non-image uploads bypass decoding unchanged',async()=>{
 const v=setup(),jpg={...photo,type:'image/jpeg',size:800*1024},pdf={name:'fictional-report.pdf',type:'application/pdf',size:42}
 assert.strictEqual(await v.compressImageFile(jpg),jpg);assert.strictEqual(await v.compressImageFile(pdf),pdf);assert.equal(v.created.length,0)
})
test('null encoded blobs retain the original',async()=>{
 const v=setup({document:{createElement(){return{getContext(){return{drawImage(){}}},toBlob(callback){callback(null)}}}}})
 const result=v.compressImageFile(photo);v.images[0].onload();assert.strictEqual(await result,photo)
})
test('a missing canvas context retains the original',async()=>{
 const v=setup({document:{createElement(){return{getContext(){return null}}}}})
 const result=v.compressImageFile(photo);v.images[0].onload();assert.strictEqual(await result,photo)
})

// Render the actual SFC through Vite, with synthetic API data and real DOMPurify.
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync,readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {createRequire} from 'node:module';
import {createServer,build} from 'vite';
import vue from '@vitejs/plugin-vue';
const require=createRequire(import.meta.url),{chromium}=require('playwright');
const repo=fileURLToPath(new URL('../../',import.meta.url));
const dependencyRoot=join(repo,'frontend');
const dependencies=Object.keys(JSON.parse(readFileSync(join(dependencyRoot,'package.json'),'utf8')).dependencies);
const compileOnly=process.argv.includes('--compile-only');
const browser=compileOnly?null:await chromium.launch({...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}),headless:true,args:['--no-sandbox'],timeout:30000});
try {
  for(const edition of ['en','zh']) {
    const frontend=join(repo,edition==='en'?'frontend':'cn/frontend');
    // Both locale sources use the one pinned dependency installation from CI.
    const harness=mkdtempSync(join(dependencyRoot,'.dialysis-language-qa-'));
    let server;
    try {
      writeFileSync(join(harness,'index.html'),`<!doctype html><html lang="${edition==='en'?'en':'zh-CN'}"><body><div id="app"></div><script type="module" src="/main.js"></script></body></html>`);
      writeFileSync(join(harness,'main.js'),`
        import {createApp} from 'vue';
        import {createRouter,createMemoryHistory} from 'vue-router';
        import ElementPlus from 'element-plus';
        import locale from 'element-plus/es/locale/lang/${edition==='en'?'en':'zh-cn'}';
        import * as icons from '@element-plus/icons-vue';
        import 'element-plus/dist/index.css';
        import DialysisManager from '/@fs/${frontend}/src/views/DialysisManager.vue';
        const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:DialysisManager}]});
        router.push('/?tab=ai').then(()=>{
          const app=createApp(DialysisManager);app.use(router);app.use(ElementPlus,{locale});
          for(const [name,icon] of Object.entries(icons))app.component(name,icon);
          app.mount('#app');
        });
      `);
      const config={configFile:false,root:harness,plugins:[vue()],resolve:{alias:{'@':join(frontend,'src')},dedupe:dependencies},server:{host:'127.0.0.1',port:0,fs:{allow:[repo]}},logLevel:'error'};
      if(compileOnly){await build({...config,build:{outDir:join(harness,'dist'),reportCompressedSize:false}});console.log(`${edition}: actual SFC browser harness compiled`);continue;}
      server=await createServer(config);
      await server.listen();
      const address=server.httpServer.address(),base=`http://127.0.0.1:${address.port}`;
      for(const width of [1440,390]) {
        const page=await browser.newPage({viewport:{width,height:900}}),errors=[];
        page.setDefaultTimeout(30000);page.setDefaultNavigationTimeout(30000);
        page.on('pageerror',error=>errors.push(error.message));
        await page.addInitScript(()=>{localStorage.setItem('token','synthetic-browser-only');localStorage.setItem('currentPatientId','1');});
        let narrative='';
        await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
          const path=new URL(route.request().url()).pathname;
          const data=path==='/api/ai/analyze'?{rawText:narrative}:path==='/api/patient/names'?[{id:1,patientName:'Synthetic patient'}]:[];
          await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,data})});
        });
        for(const heading of ['【Dry weight adjustment conclusion】','【干体重调整结论】','【Dry Weightadjustment conclusion】']) {
          narrative=`Original narrative 原始记录\n<img src=x onerror="window.injected=true"><script>window.injected=true</script>\n${heading}\nDose: metformin 500 mg\nSource wording: 原话 unchanged`;
          await page.goto(base);
          await page.locator('.ai-content .ai-dw-conclusion').waitFor();
          const host=page.locator('.ai-content'),text=await host.textContent();
          assert.equal(await host.locator('script,img,svg,[onerror],[onload]').count(),0);
          assert.equal(await page.evaluate(()=>!!window.injected),false);
          assert.ok(text.includes('Original narrative 原始记录'));assert.ok(text.includes(heading));
          assert.ok(text.includes('metformin 500 mg'));assert.ok(text.includes('原话 unchanged'));
        }
        assert.deepEqual(errors,[]);
        console.log(`${edition} ${width}px: actual DialysisManager SFC, current/legacy headings, source text and real DOMPurify verified`);
        await page.close();
      }
    } finally {if(server)await server.close();rmSync(harness,{recursive:true,force:true});}
  }
} finally {await browser?.close();}

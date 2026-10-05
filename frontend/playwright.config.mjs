import { defineConfig } from '@playwright/test'
if(process.env.CARE_PLAN_BROWSER_REQUIRED!=='true' || process.env.CARE_PLAN_TEST_ONLY!=='true' || process.env.CARE_PLAN_E2E_BASE_URL!=='http://127.0.0.1:14173')throw new Error('Required real-browser disposable configuration absent')
export default defineConfig({
  testDir:'./e2e',outputDir:'./test-results',fullyParallel:false,workers:1,retries:0,timeout:90000,
  reporter:[['line'],['../scripts/care-plan-browser-outcomes.mjs',{edition:'en'}]],forbidOnly:true,
  use:{baseURL:process.env.CARE_PLAN_E2E_BASE_URL,browserName:'chromium',headless:true,viewport:{width:1365,height:900},timezoneId:'Asia/Shanghai',
    video:'on',screenshot:'only-on-failure',trace:'off'},
})

<template>
  <el-dialog v-model="welcomeVisible" title="欢迎使用健康工作台" width="min(480px, 94vw)" :close-on-click-modal="false">
    <div class="guide-welcome"><el-icon :size="34"><Guide /></el-icon><div><h3>是否需要开启新手指引？</h3><p>系统每次只讲解一个功能，指引过程不会提交或修改任何健康数据。</p></div></div>
    <template #footer><el-button @click="dismissWelcome">以后再说</el-button><el-button type="primary" @click="begin">开始逐步指引</el-button></template>
  </el-dialog>
  <div v-if="active" class="guide-layer" role="dialog" aria-modal="true" :aria-labelledby="`guide-title-${stepIndex}`">
    <div class="guide-card">
      <header><span>新手指引 · {{ stepIndex + 1 }} / {{ steps.length }}</span><el-button text @click="pause">暂停</el-button></header>
      <el-progress :percentage="progress" :show-text="false" :stroke-width="5" />
      <div class="guide-copy"><el-icon :size="28"><component :is="current.icon" /></el-icon><div><h2 :id="`guide-title-${stepIndex}`">{{ current.title }}</h2><p>{{ current.description }}</p><div class="guide-action"><strong>请尝试</strong><span>{{ current.action }}</span></div></div></div>
      <el-checkbox v-model="confirmed">我已看懂并完成本步骤。</el-checkbox>
      <footer><el-button :disabled="stepIndex === 0" @click="previous">上一步</el-button><el-button type="primary" :disabled="!confirmed" @click="confirmStep">{{ stepIndex === steps.length - 1 ? '完成指引' : '确认并继续' }}</el-button></footer>
    </div>
  </div>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Guide, Menu, Search, User, FirstAidKit, Odometer, House, Setting, Switch } from '@element-plus/icons-vue';
const props = defineProps({ accountId: { type: [String, Number], default: '' }, roleCodes: { type: Array, default: () => [] } });
const router = useRouter();
const welcomeVisible = ref(false), active = ref(false), confirmed = ref(false), stepIndex = ref(0);
const role = computed(() => ['admin','doctor','patient','family'].find(code => props.roleCodes.includes(code)) || 'patient');
const commonEnd = [
  { title:'快速找到任意功能', description:'搜索是查找血压、用药、档案、报告和设置最快的方式。', action:'打开“查找功能”，输入关键词并选择结果。', selector:'.workspace-sidebar', icon:Search },
  { title:'确认当前查看的患者', description:'所有记录和操作都使用顶部选中的患者。录入健康信息前必须先确认患者。', action:'每次录入或复核数据前，检查右上角的患者切换器。', selector:'.workspace-topbar', icon:Switch },
  { title:'安全管理账号', description:'账号面板包含修改密码和退出登录。照护团队成员之间不要共用账号。', action:'需要账号操作时，从左下角打开账号面板。', selector:'.workspace-sidebar', icon:User }
];
const roleSteps = {
  doctor:[{ title:'从医生工作台开始', description:'先查看负责患者、待临床复核、照护计划和紧急信号，再处理日常记录。', action:'打开医生工作台，按照优先级从高到低处理事项。', path:'/doctor-workspace', selector:'.workspace-content', icon:FirstAidKit }],
  patient:[{ title:'从今日照护开始', description:'在个人工作台完成用药、测量、预约和随访任务。', action:'需要测量时打开“血压血糖”，然后核对患者和测量时间。', path:'/bp-self-monitor', selector:'.workspace-content', icon:Odometer }],
  family:[{ title:'协同完成家庭照护', description:'通过家庭工作台完成共享任务、检查服药情况并留下清晰交接记录。', action:'打开家庭照护，选择正确家人，查看尚未完成的任务。', path:'/care', selector:'.workspace-content', icon:House }],
  admin:[{ title:'管理平台但不录入临床数据', description:'系统管理负责用户、角色、菜单、品牌和审计；患者照护仍在临床工作台完成。', action:'打开用户管理，修改账号前先核对角色范围。', path:'/system/user', selector:'.workspace-content', icon:Setting }]
};
const steps = computed(() => [{ title:'认识工作台', description:'左侧导航会根据你的身份显示日常照护、记录分析和系统管理功能。', action:'查看当前可见的菜单分组；没有权限的功能不会显示。', selector:'.workspace-sidebar', icon:Menu }, ...(roleSteps[role.value] || roleSteps.patient), ...commonEnd]);
const progress = computed(() => Math.round(((stepIndex.value + 1) / steps.value.length) * 100));
const current = computed(() => steps.value[stepIndex.value]);
const storageKey = computed(() => `care-onboarding:${props.accountId || 'guest'}:${role.value}`);
const promptKey = computed(() => `care-onboarding-prompt:${props.accountId || 'guest'}:${role.value}`);
let highlighted;
function clearHighlight(){ highlighted?.classList.remove('guide-highlight'); highlighted = null; }
async function showStep(){ clearHighlight(); confirmed.value=false; if(current.value.path && router.currentRoute.value.path!==current.value.path) await router.push(current.value.path); await nextTick(); setTimeout(()=>{ clearHighlight(); highlighted=document.querySelector(current.value.selector); highlighted?.classList.add('guide-highlight'); highlighted?.scrollIntoView?.({block:'nearest'}); },180); }
function begin(){ welcomeVisible.value=false; localStorage.setItem(promptKey.value,'seen'); const saved=Number(localStorage.getItem(storageKey.value)); stepIndex.value=Number.isInteger(saved)&&saved>=0&&saved<steps.value.length?saved:0; active.value=true; showStep(); }
function start(){ const saved=Number(localStorage.getItem(storageKey.value)); stepIndex.value=Number.isInteger(saved)&&saved>=0&&saved<steps.value.length?saved:0; active.value=true; welcomeVisible.value=false; showStep(); }
function dismissWelcome(){ welcomeVisible.value=false; localStorage.setItem(promptKey.value,'seen'); }
function pause(){ localStorage.setItem(storageKey.value,String(stepIndex.value)); active.value=false; clearHighlight(); }
function previous(){ if(stepIndex.value>0){stepIndex.value--;showStep();} }
function confirmStep(){ if(!confirmed.value)return; if(stepIndex.value===steps.value.length-1){localStorage.setItem(storageKey.value,'complete');active.value=false;clearHighlight();return;} stepIndex.value++;localStorage.setItem(storageKey.value,String(stepIndex.value));showStep(); }
watch(() => [props.accountId, props.roleCodes.join(',')], () => { if(props.accountId && !localStorage.getItem(promptKey.value) && localStorage.getItem(storageKey.value)!=='complete') welcomeVisible.value=true; }, { immediate:true });
onBeforeUnmount(clearHighlight);
defineExpose({ start });
</script>
<style scoped>
.guide-welcome{display:flex;gap:16px;align-items:flex-start}.guide-welcome .el-icon{padding:12px;border-radius:14px;background:#e9f4ef;color:#257367}.guide-welcome h3{margin:0 0 8px}.guide-welcome p{margin:0;color:#667b75;line-height:1.7}.guide-layer{position:fixed;inset:0;z-index:4800;background:rgb(10 34 29 / 45%);pointer-events:none}.guide-card{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);width:min(620px,calc(100% - 28px));padding:22px;border:1px solid #d9e5e1;border-radius:18px;background:#fff;box-shadow:0 24px 90px rgb(10 40 33 / 28%);pointer-events:auto}.guide-card header,.guide-card footer{display:flex;align-items:center;justify-content:space-between;gap:12px}.guide-card header span{font-size:10px;letter-spacing:.12em;font-weight:700;color:#347b6e}.guide-copy{display:flex;gap:16px;margin:22px 0 16px}.guide-copy>.el-icon{flex:0 0 auto;padding:10px;border-radius:13px;background:#eaf4ef;color:#287568}.guide-copy h2{margin:0 0 8px;font-size:21px}.guide-copy p{margin:0 0 13px;color:#617670;line-height:1.65}.guide-action{padding:11px 13px;border-radius:10px;background:#f4f8f6}.guide-action strong,.guide-action span{display:block}.guide-action strong{margin-bottom:3px;font-size:11px;color:#287568}.guide-action span{font-size:13px}.guide-card footer{margin-top:18px}@media(max-width:600px){.guide-card{bottom:82px;padding:18px}.guide-copy h2{font-size:18px}.guide-copy{gap:10px}.guide-card footer .el-button{flex:1;margin:0}}
</style>
<style>.guide-highlight{position:relative!important;z-index:4900!important;outline:4px solid #67c7b3!important;outline-offset:5px!important;box-shadow:0 0 0 10px rgb(103 199 179 / 20%)!important;border-radius:10px!important}</style>

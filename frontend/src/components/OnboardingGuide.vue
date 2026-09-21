<template>
  <el-dialog v-model="welcomeVisible" title="Welcome to your health workspace" width="min(480px, 94vw)" :close-on-click-modal="false">
    <div class="guide-welcome"><el-icon :size="34"><Guide /></el-icon><div><h3>Would you like a guided introduction?</h3><p>We explain one feature at a time. The guide never submits or changes health data.</p></div></div>
    <template #footer><el-button @click="dismissWelcome">Maybe Later</el-button><el-button type="primary" @click="begin">Start Step-by-Step Guide</el-button></template>
  </el-dialog>
  <div v-if="active" class="guide-layer" role="dialog" aria-modal="true" :aria-labelledby="`guide-title-${stepIndex}`">
    <div class="guide-card">
      <header><span>GUIDED MODE · {{ stepIndex + 1 }} / {{ steps.length }}</span><el-button text @click="pause">Pause</el-button></header>
      <el-progress :percentage="progress" :show-text="false" :stroke-width="5" />
      <div class="guide-copy"><el-icon :size="28"><component :is="current.icon" /></el-icon><div><h2 :id="`guide-title-${stepIndex}`">{{ current.title }}</h2><p>{{ current.description }}</p><div class="guide-action"><strong>Try this</strong><span>{{ current.action }}</span></div></div></div>
      <el-checkbox v-model="confirmed">I have reviewed and completed this step.</el-checkbox>
      <footer><el-button :disabled="stepIndex === 0" @click="previous">Previous</el-button><el-button type="primary" :disabled="!confirmed" @click="confirmStep">{{ stepIndex === steps.length - 1 ? 'Finish Guide' : 'Confirm and Continue' }}</el-button></footer>
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
  { title:'Find any feature quickly', description:'Search is the fastest way to locate blood pressure, medications, records, reports, and settings.', action:'Open “Find a feature”, enter a keyword, and select a result.', selector:'.workspace-sidebar', icon:Search },
  { title:'Know which patient you are viewing', description:'Every record and action uses the patient selected in the header. Confirm the patient before entering health information.', action:'Check the patient switcher in the top-right corner before recording or reviewing data.', selector:'.workspace-topbar', icon:Switch },
  { title:'Manage your account safely', description:'Your account panel contains password and sign-out controls. Never share an account between care-team members.', action:'Open the account panel from the lower-left corner when you need account controls.', selector:'.workspace-sidebar', icon:User }
];
const roleSteps = {
  doctor:[{ title:'Start from the doctor workspace', description:'Review assigned patients, pending clinical reviews, care plans, and urgent signals before routine records.', action:'Open Doctor Workspace and work from highest-priority items downward.', path:'/doctor-workspace', selector:'.workspace-content', icon:FirstAidKit }],
  patient:[{ title:'Start with today’s care', description:'Complete medication, measurements, appointments, and follow-up tasks from your personal workspace.', action:'Open Blood Pressure & Glucose when a reading is due, then verify the patient and time.', path:'/bp-self-monitor', selector:'.workspace-content', icon:Odometer }],
  family:[{ title:'Coordinate family care', description:'Use the family workspace to complete shared tasks, check adherence, and leave clear handover notes.', action:'Open Family Care, select the correct family member, and review unfinished tasks.', path:'/care', selector:'.workspace-content', icon:House }],
  admin:[{ title:'Administer without entering clinical data', description:'Administration controls users, roles, menus, branding, and audit records. Patient care remains in clinical workspaces.', action:'Open User Management and verify role scope before changing an account.', path:'/system/user', selector:'.workspace-content', icon:Setting }]
};
const steps = computed(() => [{ title:'Understand the workspace', description:'The left navigation groups everyday care, records and analytics, and administration according to your role.', action:'Review the visible menu groups. Unauthorized features stay hidden.', selector:'.workspace-sidebar', icon:Menu }, ...(roleSteps[role.value] || roleSteps.patient), ...commonEnd]);
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

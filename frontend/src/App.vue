<template>
  <div class="care-workspace" :class="{ 'care-workspace--guest': route.meta.hideNav }">
    <div v-if="route.meta.hideNav" class="workspace-guest-actions"><AppearanceSelector :compact="isMobile" /><a class="workspace-language" :href="languageHref" hreflang="zh-CN">中文</a></div>
    <template v-if="!route.meta.hideNav">
      <a class="skip-content" href="#workspace-content">Skip to main content</a>
      <aside v-if="!isMobile" class="workspace-sidebar">
        <router-link to="/care" class="workspace-brand" :aria-label="`${platformBranding.platformName} home`">
          <span class="workspace-brand__mark"><img :src="platformBranding.logo" alt="" width="30" height="30" /></span>
          <span><strong>{{ platformBranding.platformName }}</strong><small>{{ platformBranding.organizationName }}</small></span>
        </router-link>
        <button class="workspace-search" data-guide="feature-search" type="button" @click="openSearch"><el-icon><Search /></el-icon><span>Find a feature</span><kbd>Ctrl K</kbd></button>
        <WorkspaceNav :groups="navigationGroups" :active-label="activeModule?.label" />
        <div class="workspace-sidebar__footer">
          <div class="workspace-utility-panel workspace-utility-panel--compact">
            <button type="button" class="workspace-account workspace-utility-identity" @click="accountVisible = true">
              <span class="workspace-account__avatar">{{ accountName.slice(0, 1) }}</span>
              <span class="workspace-utility-copy"><strong>{{ accountName }}</strong><small>{{ userRoles }}</small></span>
            </button>
            <button type="button" class="workspace-guide workspace-utility-guide" title="Learn this page step by step" aria-label="Start guided tour" @click="guideRef?.start()"><el-icon><Guide /></el-icon><span>Guide</span></button>
          </div>
        </div>
      </aside>

      <header class="workspace-topbar">
        <div class="workspace-topbar__location">
          <button v-if="isMobile" type="button" class="workspace-icon-button" aria-label="Open navigation menu" @click="mobileNavVisible = true"><el-icon :size="21"><Menu /></el-icon></button>
          <span class="workspace-breadcrumb"><strong>{{ activeModule?.label || 'Health Management' }}</strong></span>
          <strong v-if="isMobile" class="workspace-mobile-brand">{{ platformBranding.platformName }}</strong>
        </div>
        <div class="workspace-topbar__patient">
          <PatientSwitcher :model-value="currentPatientId || 0" :patients="route.path === '/care' ? patientList : appPatientList" :allow-all="route.path !== '/care'" @update:model-value="switchPatient" />
          <router-link v-if="userInfo.roles?.some(role => role.roleCode === 'doctor')" class="workspace-consultations" to="/care-journey?tab=consultation" aria-label="Open consultation inbox" title="Consultations"><el-icon><ChatDotRound /></el-icon><span v-if="!isMobile">Consultations</span></router-link>
          <button v-if="isMobile" type="button" class="workspace-icon-button" data-guide="feature-search" aria-label="Find a feature" @click="openSearch"><el-icon :size="20"><Search /></el-icon></button>
        </div>
        <div class="workspace-topbar__actions">
          <span class="workspace-date">{{ todayLabel }}</span>
          <AppearanceSelector :compact="isMobile" />
          <a class="workspace-language" :href="languageHref" hreflang="zh-CN">中文</a>
        </div>
      </header>

      <el-drawer v-model="mobileNavVisible" direction="ltr" size="min(300px, 88vw)" :with-header="false" class="workspace-drawer" append-to-body>
        <div class="workspace-drawer__heading"><strong>{{ platformBranding.platformName }}</strong><button type="button" class="workspace-icon-button" aria-label="Close navigation menu" @click="mobileNavVisible = false"><el-icon><Close /></el-icon></button></div>
        <WorkspaceNav :groups="navigationGroups" :active-label="activeModule?.label" @navigate="mobileNavVisible = false" />
        <div class="workspace-sidebar__footer workspace-sidebar__footer--drawer"><div class="workspace-utility-panel workspace-utility-panel--compact">
          <button type="button" class="workspace-account workspace-utility-identity" @click="mobileNavVisible = false; accountVisible = true"><span class="workspace-account__avatar">{{ accountName.slice(0, 1) }}</span><span class="workspace-utility-copy"><strong>{{ accountName }}</strong><small>{{ userRoles }}</small></span></button>
          <button type="button" class="workspace-guide workspace-utility-guide" title="Learn this page step by step" aria-label="Start guided tour" @click="mobileNavVisible = false; guideRef?.start()"><el-icon><Guide /></el-icon><span>Guide</span></button>
        </div></div>
      </el-drawer>

      <nav v-if="isMobile" class="workspace-bottom-nav" aria-label="Primary navigation">
        <router-link v-for="item in mobilePrimaryNav" :key="item.path" :to="item.entryPath || item.path" :class="{ 'is-active': isItemActive(item) }" :aria-current="isItemActive(item) ? 'page' : undefined"><el-icon :size="21"><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></router-link>
        <button type="button" @click="mobileNavVisible = true"><el-icon :size="21"><Menu /></el-icon><span>All Features</span></button>
      </nav>
    </template>

    <main id="workspace-content" class="workspace-content" tabindex="-1">
      <el-alert v-if="!route.meta.hideNav && menuLoadError" type="warning" :closable="false" show-icon class="workspace-menu-error">
        <template #title>{{ menuLoadError }}</template>
        <el-button size="small" :loading="menusLoading" @click="loadUserMenus">Reload feature menu</el-button>
      </el-alert>
      <nav v-if="!route.meta.hideNav && contextTabs.length > 1" class="workspace-tabs" aria-label="Module features">
        <router-link v-for="item in contextTabs" :key="item.path" :to="item.entryPath || item.path" :class="{ 'is-active': isTabActive(item) }" :aria-current="isTabActive(item) ? 'page' : undefined"><el-icon :size="16"><component :is="item.icon" /></el-icon>{{ item.label }}</router-link>
      </nav>
      <el-alert v-if="privacyAccessBlocked && !route.meta.hideNav" type="warning" :closable="false" role="alert">
        <template #title>Access changed or could not be verified. Health information has been cleared from this page.</template>
        <el-button :loading="privacyRetrying" @click="retryProtectedAccess">Check access again</el-button>
      </el-alert>
      <router-view v-else v-slot="{ Component }"><component :is="Component" :key="route.path === '/login' ? route.fullPath : routerViewKey" /></router-view>
      <footer v-if="!route.meta.hideNav" class="platform-ownership">{{ platformBranding.ownershipText }}</footer>
    </main>

    <OnboardingGuide v-if="!route.meta.hideNav" ref="guideRef" :account-id="userInfo.id || ''" :role-codes="userInfo.roles?.map(role => role.roleCode) || []" />

    <el-dialog v-model="searchVisible" title="Find a feature" width="520px" class="workspace-search-dialog" @opened="searchInput?.focus()">
      <el-input ref="searchInput" v-model="searchQuery" placeholder="Search blood pressure, medications, reports, and more" :prefix-icon="Search" clearable size="large" aria-label="Search features" @keydown.enter="openFirstResult" @keydown.down="moveSearchResult(1, $event)" @keydown.up="moveSearchResult(-1, $event)" aria-controls="feature-search-results" :aria-activedescendant="searchResults.length ? `feature-result-${activeSearchIndex}` : undefined" />
      <p class="sr-only" role="status" aria-live="polite">{{ searchResults.length }} results</p>
      <div id="feature-search-results" class="workspace-search-results">
        <router-link v-for="(item, index) in searchResults" :key="item.path" :id="`feature-result-${index}`" :class="{ 'is-search-active': index === activeSearchIndex }" @focus="activeSearchIndex=index" :to="item.entryPath || item.path" @click="searchVisible = false"><el-icon :size="19"><component :is="item.icon" /></el-icon><span><strong>{{ item.label }}</strong><small>{{ item.group }}</small></span><el-icon><ArrowRight /></el-icon></router-link>
        <el-empty v-if="!searchResults.length" description="No matching feature. Try another keyword." :image-size="64" />
      </div>
      <template #footer><span class="workspace-search-hint">↑↓ to select · Enter to open selection · Esc to close</span></template>
    </el-dialog>
    <el-dialog v-model="accountVisible" title="Current Account" width="min(380px, 94vw)">
      <div class="workspace-account-summary"><span class="workspace-account__avatar">{{ accountName.slice(0, 1) }}</span><div><strong>{{ accountName }}</strong><p>{{ userRoles }}</p></div></div>
      <template #footer><el-button @click="accountVisible = false">Close</el-button><el-button @click="openPasswordDialog">Change Password</el-button><el-button type="danger" plain @click="accountVisible = false; handleLogout()">Sign Out</el-button></template>
    </el-dialog>
    <el-dialog v-model="passwordVisible" title="Change Password" width="min(420px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="submitPasswordChange">
        <el-form-item label="Current Password"><el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="New Password"><el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" /><small>Use at least 10 characters with letters, numbers, and special characters.</small></el-form-item>
        <el-form-item label="Confirm New Password"><el-input v-model="passwordForm.confirmPassword" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="passwordVisible = false">Cancel</el-button><el-button type="primary" :loading="passwordChanging" @click="submitPasswordChange">Save and Sign In Again</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { useMedicationNotifications } from '@/composables/useMedicationNotifications';
useMedicationNotifications();
import { ref, reactive, computed, nextTick, onMounted, onUnmounted, watch, provide } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Search, ArrowRight, Menu, Close, Guide, ChatDotRound } from '@element-plus/icons-vue';
import WorkspaceNav from '@/components/WorkspaceNav.vue';
import AppearanceSelector from '@/components/AppearanceSelector.vue';
import PatientSwitcher from '@/components/PatientSwitcher.vue';
import OnboardingGuide from '@/components/OnboardingGuide.vue';
import { logout, getUserInfo } from '@/api/auth';
import { normalizeWorkspaceMenus, getFallbackWorkspaceMenus, getEnglishMenuLabel } from '@/utils/workspaceNavigation';
import { getAuthSessionKey, saveAuthSession, captureAuthSession, isAuthSessionCurrent, clearPermissionCache, savePermissionCache, readPermissionCache } from '@/utils/authSession';
import { DEFAULT_WORKSPACE_TABS, canAccessWorkspace, resolveWorkspaceEntry, isNarrowCareRoute, parseReportRoute } from '@/utils/workspaceAccess';
import { capabilities as getAlertWorkflowCapabilities } from '@/api/alertWorkflow';
import { getCarePlanCapabilities } from '@/api/carePlan';
import { getPatientNames } from '@/api/patient';
import { filterSpecialtyMenus, knownSpecialtyPath, specialtyPathAllowed } from '@/utils/patientSpecialtyNavigation';
import { clearPatientSpecialtyScope, loadPatientSpecialtyScope } from '@/utils/patientSpecialtyScope';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';
import { ElMessage } from 'element-plus';
import { changePassword } from '@/api/user';
import { platformBranding } from '@/utils/platformBranding';
import request from '@/utils/request';

const route = useRoute();
const router = useRouter();
const languageHref = computed(() => `/cn${route.fullPath === '/' ? '/' : route.fullPath}`);
const { isMobile } = useMobile();

const alertWorkflowNavigationEnabled=ref(false);
const carePlanNavigationEnabled=ref(false);let carePlanNavController=null;
async function refreshCarePlanNavigation(session,actor){carePlanNavController?.abort();const controller=new AbortController();carePlanNavController=controller;carePlanNavigationEnabled.value=false;alertWorkflowNavigationEnabled.value=false;const options={expectedAuth:{...session,actorId:actor},signal:controller.signal};const owns=()=>carePlanNavController===controller&&isAuthSessionCurrent(session)&&actor===localStorage.getItem('userId');await Promise.allSettled([getCarePlanCapabilities(options).then(response=>{if(owns())carePlanNavigationEnabled.value=response.data?.enabled===true}),getAlertWorkflowCapabilities(null,options).then(response=>{if(owns())alertWorkflowNavigationEnabled.value=response.data?.enabled===true})])}

const rawNavItems = ref([]);
const specialtyScope = ref(null);
const navItems = computed(() => {
  const filterCollaboration=items=>(items||[]).filter(item=>item.path!=='/nurse-workspace'||carePlanNavigationEnabled.value||alertWorkflowNavigationEnabled.value).map(item=>({...item,children:filterCollaboration(item.children)}));
  const filtered = filterCollaboration(filterSpecialtyMenus(rawNavItems.value, specialtyScope.value));
  if((carePlanNavigationEnabled.value||alertWorkflowNavigationEnabled.value)&&userInfo.value.roles?.some(role=>role.roleCode==='nurse')&&!filtered.some(item=>item.path==='/nurse-workspace'))filtered.push({path:'/nurse-workspace',entryPath:'/nurse-workspace',label:'Nursing Follow-up',icon:'FirstAidKit',children:[]});
  return [{ path: '/care', entryPath: '/care', label: 'Family Care', icon: 'House', children: [] }, ...filtered.filter(item => item.path !== '/care')];
});
let specialtyEpoch = 0;
async function refreshSpecialtyScope() {
  const epoch = ++specialtyEpoch;
  const patientId = currentPatientId.value;
  specialtyScope.value = null;
  if (!localStorage.getItem('token') || route.meta.hideNav || isNarrowCareRoute(route.fullPath)) return;
  const scope = await loadPatientSpecialtyScope(patientId);
  if (epoch !== specialtyEpoch || patientId !== currentPatientId.value) return;
  specialtyScope.value = scope;
  if (!route.meta.hideNav && !specialtyPathAllowed(route.fullPath, scope)
      && (knownSpecialtyPath(route.fullPath) || scope?.restrictedPaths?.includes(route.fullPath))) {
    router.replace('/monitoring');
  }
}
const menuLoadError = ref('');
const menusLoading = ref(false);
let menuRequestEpoch = 0;
let patientRequestEpoch = 0;
const mobileNavVisible = ref(false);
const userInfo = ref({});
let profileContinuation = null;
let sessionReloadTimer = null;
let navFreshRequired = false;
let appDisposed = false;

function cancelSessionReload() {
  if (sessionReloadTimer !== null) clearTimeout(sessionReloadTimer);
  sessionReloadTimer = null;
}

function authSessionChanged() {
  const own = profileContinuation;
  // 只消费本次同步资料保存产生的一个修订；同账号/同token的外部变化也必须退休旧视图。
  profileContinuation = null;
  const session = captureAuthSession();
  const actor = localStorage.getItem('userId');
  if (own && own.token === session.token && own.actor === actor &&
      own.revision === session.revision && own.epoch === menuRequestEpoch) return;
  privacyRecoveryEpoch++;
  privacyRevision = null;
  privacyAccessBlocked.value = false;
  navFreshRequired = true;
  resetNavState({ localOnly: true });
  if (appDisposed || !session.token || !actor || route.path === '/login') return;
  // storage会连发多个事件；合并到下一轮，并只读取届时仍有效的完整身份，不自动重试失败。
  sessionReloadTimer = setTimeout(() => {
    sessionReloadTimer = null;
    if (!appDisposed && route.path !== '/login' && localStorage.getItem('token') && localStorage.getItem('userId')) void loadUserMenus();
  }, 0);
}

const systemMenu = ref(null);
const routerViewKey = ref(getAuthSessionKey());
const privacyAccessBlocked = ref(false);
const privacyRetrying = ref(false);
let privacyRevision = null, privacyTimer = null, privacyChecking = false, privacyCheckQueued = false, privacyViewRevision = 0, privacyRecoveryEpoch = 0;
function retireProtectedView() {
  if (route.meta.hideNav) return;
  privacyRecoveryEpoch++;
  privacyAccessBlocked.value = true;
  privacyRevision = null;
  clearPermissionCache();
  resetNavState({ localOnly: true });
}
async function checkAccessContext() {
  if (privacyChecking) { privacyCheckQueued = true; return; }
  if (privacyAccessBlocked.value || route.meta.hideNav || document.visibilityState === 'hidden' || !localStorage.getItem('token')) return;
  privacyChecking = true;
  const session = captureAuthSession();
  try {
    const response = await request({ url: '/privacy/access-context', privacyInlineErrors: true });
    if (!isAuthSessionCurrent(session)) return;
    const next = response.data?.revision;
    if (typeof next !== 'string' || !/^[a-f0-9]{64}$/.test(next)) throw new Error('ACCESS_CONTEXT_UNAVAILABLE');
    if (privacyRevision !== null && privacyRevision !== next) window.dispatchEvent(new Event('permissions-changed'));
    else privacyRevision = next;
  } catch (error) {
    if (error.code !== 'ERR_CANCELED' && isAuthSessionCurrent(session)) window.dispatchEvent(new Event('permissions-changed'));
  } finally {
    privacyChecking = false;
    if (privacyCheckQueued) { privacyCheckQueued = false; void checkAccessContext(); }
  }
}
async function retryProtectedAccess() {
  if (privacyRetrying.value) return;
  privacyRetrying.value = true;
  const epoch = ++privacyRecoveryEpoch, session = captureAuthSession(), actor = localStorage.getItem('userId');
  try {
    const response = await request({ url: '/privacy/access-context', privacyInlineErrors: true });
    if (epoch !== privacyRecoveryEpoch || !isAuthSessionCurrent(session) || actor !== localStorage.getItem('userId')) return;
    if (!/^[a-f0-9]{64}$/.test(response.data?.revision || '')) return;
    privacyRevision = response.data.revision;
    await loadUserMenus();
    if (epoch === privacyRecoveryEpoch && session.token === localStorage.getItem('token') && actor === localStorage.getItem('userId') && !menuLoadError.value && !navFreshRequired) privacyAccessBlocked.value = false;
  } catch { /* Keep protected views unmounted until a successful explicit check. */ }
  finally { privacyRetrying.value = false; }
}
const userMenus = ref([]);
const passwordVisible = ref(false);
const passwordChanging = ref(false);
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' });

// alllayoutcurrentPatientStatus
const { currentPatientId, patientList, setPatientList, resetLocalPatientContext } = useCurrentPatient();
const appPatientList = ref([]);
let savedSeniorMode = false;
try { savedSeniorMode = localStorage.getItem('care-senior') === 'true'; } catch { /* Use standard text when preference storage is unavailable. */ }
document.body.classList.toggle('care-senior', savedSeniorMode);


// towardchildcomponentraiseprovideuserMenuPermission
provide('userMenus', userMenus);

function specialtyChanged() {
  clearPatientSpecialtyScope();
  refreshSpecialtyScope();
}

function resetNavState(options = {}) {
  cancelSessionReload();
  closePatientSwitchToast();
  carePlanNavController?.abort();carePlanNavigationEnabled.value=false;alertWorkflowNavigationEnabled.value=false;
  menuRequestEpoch++;
  patientRequestEpoch++;
  specialtyEpoch++;
  specialtyScope.value = null;
  clearPatientSpecialtyScope();
  menuLoadError.value = '';
  menusLoading.value = false;
  rawNavItems.value = [];
  userInfo.value = {};
  systemMenu.value = null;
  routerViewKey.value = `${getAuthSessionKey()}-${captureAuthSession().revision}`;
  if (!options.localOnly) clearPermissionCache();
  appPatientList.value = [];
  userMenus.value = [];
  if (options.localOnly) resetLocalPatientContext();
  else setPatientList([]);
}

const userRoles = computed(() => {
  return userInfo.value.roles?.map(r => r.roleName).join(', ') || 'Standard User';
});

const mobilePrimaryNav = computed(() => {
  const preferredPaths = userInfo.value.roles?.some(role => role.roleCode === 'doctor') ? ['/doctor-workspace', '/clinical-workbench', '/monitoring'] : ['/care', '/monitoring', '/medication'];
  const pathItems = [...navItems.value.filter(item => item.path && item.path !== '/dashboard'), ...navItems.value.flatMap(item => item.children || []).filter(item => item.path === '/bp-self-monitor')];
  const preferred = preferredPaths
    .map(path => pathItems.find(item => item.path === path))
    .filter(Boolean);
  const remaining = pathItems.filter(item => !preferred.some(current => current.path === item.path));
  return [...preferred, ...remaining].slice(0, 3);
});

const searchVisible = ref(false);
const searchInput = ref(null);
const searchQuery = ref('');
const accountVisible = ref(false);
const guideRef = ref(null);
const accountName = computed(() => userInfo.value.realName || userInfo.value.username || 'My Account');
const todayLabel = new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date());
const navigationGroups = computed(() => {
  const daily = [], records = [], management = [];
  for (const item of navItems.value) {
    if (item.path === '/dashboard') continue;
    if (item.label === 'System Administration') { management.push(item); continue; }
    if (['/dialysis', '/medical-record', '/medication', '/dry-weight'].includes(item.path) || /analysis|Report/.test(item.label)) records.push(item);
    else daily.push(item);
  }
  if (systemMenu.value?.children?.length && !management.length) management.push(systemMenu.value);
  return [{ label: 'Everyday Health', items: daily }, { label: 'Records & Analytics', items: records }, { label: 'Administration', items: management }].filter(group => group.items.length);
});
const allNavigation = computed(() => navigationGroups.value.flatMap(group => group.items));
const activeModule = computed(() => {
  const matchesTab = child => {
    const target = router.resolve(child.path);
    return target.path === route.path && target.query.tab && target.query.tab === route.query.tab;
  };
  return allNavigation.value.find(item => item.children?.some(matchesTab))
    || allNavigation.value.find(item => item.path && router.resolve(item.path).path === route.path)
    || allNavigation.value.find(item => item.children?.some(child => router.resolve(child.path).path === route.path));
});
watch([activeModule, () => platformBranding.platformName], ([item]) => { document.title = (item?.label ? item.label + ' · ' : '') + platformBranding.platformName; }, { immediate: true });
const contextTabs = computed(() => {
  const item = activeModule.value;
  const children = item?.children || [];
  const defaultTab = defaultTabs[item?.path];
  const permissions = readPermissionCache() || {};
  if (defaultTab && !canAccessWorkspace(`${item.path}?tab=${defaultTab}`, permissions.menuPaths, permissions.roleCodes)) return children;
  if (!children.length || !defaultTab || children.some(child => router.resolve(child.path).query.tab === defaultTab && router.resolve(child.path).path === item.path)) return children;
  const labels = { '/dialysis': 'Dialysis Records', '/medical-record': 'Record List', '/medication': 'Medication List', '/health-analysis': 'Complication Tracking' };
  return labels[item.path] ? [{ ...item, label: labels[item.path], children: [] }, ...children] : children;
});
const defaultTabs = DEFAULT_WORKSPACE_TABS;
function isTabActive(item) {
  const target = router.resolve(item.path);
  if (target.path !== route.path) return false;
  return (target.query.tab || defaultTabs[target.path] || '') === (route.query.tab || defaultTabs[route.path] || '');
}
const searchResults = computed(() => {
  const entries = new Map();
  for (const item of allNavigation.value) {
    if (item.path) entries.set(item.path, { ...item, group: 'Feature' });
    for (const child of item.children || []) entries.set(child.path, { ...child, group: item.label });
  }
  const query = searchQuery.value.trim().toLowerCase();
  return [...entries.values()].filter(item => (item.label + item.group).toLowerCase().includes(query));
});
const activeSearchIndex = ref(0);
watch(searchQuery, () => { activeSearchIndex.value = 0; });
watch(searchResults, results => { activeSearchIndex.value = Math.min(activeSearchIndex.value, Math.max(0, results.length - 1)); });
function openSearch() { closePatientSwitchToast(); searchQuery.value = ''; activeSearchIndex.value = 0; searchVisible.value = true; }
function moveSearchResult(direction, event) {
  if (event?.isComposing || event?.keyCode === 229) return;
  event?.preventDefault();
  const length = searchResults.value.length;
  activeSearchIndex.value = length ? (activeSearchIndex.value + direction + length) % length : 0;
  document.getElementById(`feature-result-${activeSearchIndex.value}`)?.scrollIntoView?.({ block: 'nearest' });
}
function openFirstResult(event) {
  if (event?.isComposing || event?.keyCode === 229) return;
  event?.preventDefault();
  event?.stopPropagation();
  const item = searchResults.value[activeSearchIndex.value];
  if (!item) return;
  router.push(item.entryPath || item.path);
  searchVisible.value = false;
}
function onSearchShortcut(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && !route.meta.hideNav) {
    event.preventDefault(); openSearch();
  }
}
onMounted(() => {
  window.addEventListener('permissions-changed', retireProtectedView);
  window.addEventListener('protected-access-denied', checkAccessContext);
  window.addEventListener('focus', checkAccessContext);
  document.addEventListener('visibilitychange', checkAccessContext);
  privacyTimer = setInterval(checkAccessContext, 30000);
  void checkAccessContext();
  window.addEventListener('keydown', onSearchShortcut);
  window.addEventListener('auth-session-cleared', resetNavState);
  window.addEventListener('auth-session-changed', authSessionChanged);
  window.addEventListener('patient-specialty-changed', specialtyChanged);
  window.addEventListener('care-patients-changed', loadPatientList);
});
onUnmounted(() => {
  clearInterval(privacyTimer);
  window.removeEventListener('permissions-changed', retireProtectedView);
  window.removeEventListener('protected-access-denied', checkAccessContext);
  window.removeEventListener('focus', checkAccessContext);
  document.removeEventListener('visibilitychange', checkAccessContext);
  closePatientSwitchToast();
  carePlanNavController?.abort();
  window.removeEventListener('keydown', onSearchShortcut);
  window.removeEventListener('auth-session-cleared', resetNavState);
  window.removeEventListener('auth-session-changed', authSessionChanged);
  appDisposed = true;
  menuRequestEpoch++;
  patientRequestEpoch++;
  specialtyEpoch++;
  profileContinuation = null;
  cancelSessionReload();
  window.removeEventListener('patient-specialty-changed', specialtyChanged);
  window.removeEventListener('care-patients-changed', loadPatientList);
});

// loaduserMenu
const loadUserMenus = async () => {
  let session = captureAuthSession();
  if (!session.token) return;
  let identity = localStorage.getItem('userId');
  const epoch = ++menuRequestEpoch;
  const isCurrent = () => !appDisposed && epoch === menuRequestEpoch && isAuthSessionCurrent(session) && identity === localStorage.getItem('userId');
  menusLoading.value = true;

  try {
    const res = await getUserInfo();
    if (!isCurrent() || identity !== localStorage.getItem('userId')) return;
    if (res.code === 200 && Array.isArray(res.data?.menus)) {
      menuLoadError.value = '';
      systemMenu.value = null;
      const roleCodes = (res.data.roles || []).map(role => role.roleCode);
      const adminOnlyMenu = menu => {
        const code = menu.menuCode || '';
        const permission = menu.permission || '';
        const path = menu.menuPath || '';
        return ['system', 'system-user', 'system-role', 'system-menu', 'system-audit', 'platform-branding'].includes(code)
          || ['user:manage', 'role:manage', 'menu:manage', 'audit:view', 'branding:manage'].includes(permission)
          || (path.startsWith('/system/') && path !== '/system/patient');
      };
      const allMenus = roleCodes.includes('admin') ? res.data.menus : res.data.menus.filter(menu => !adminOnlyMenu(menu));
      const profileIdentity = res.data.user?.id == null ? identity : String(res.data.user.id);
      if (identity !== null && profileIdentity !== identity) return;
      userInfo.value = res.data.user || {};
      if (res.data.roles) {
        userInfo.value.roles = res.data.roles;
      }
      if (res.data.user) {
        const own = { token: session.token, actor: profileIdentity, revision: session.revision + 1, epoch };
        profileContinuation = own;
        try {
          saveAuthSession({
            userId: res.data.user.id,
            username: res.data.user.username,
            realName: res.data.user.realName
          });
        } finally {
          if (profileContinuation === own) profileContinuation = null;
        }
        const nextSession = captureAuthSession();
        // 只接续本次同步资料保存产生的一次修订；换账号、退出或同 token ABA 仍使旧启动失效。
        if (epoch !== menuRequestEpoch || nextSession.token !== session.token || nextSession.revision !== session.revision + 1 || localStorage.getItem('userId') !== profileIdentity) return;
        session = nextSession;
        identity = profileIdentity;
      }
      if (!isCurrent()) return;
      refreshCarePlanNavigation(session,identity);
      // 资料保存已安全清空旧闭环会话；按当前修订重建路由子页，只重新读取，不恢复旧草稿或写入。
      routerViewKey.value = `${getAuthSessionKey()}-${session.revision}`;


      const menuPaths = allMenus.filter(menu => menu.menuPath).map(menu => menu.menuPath);
      savePermissionCache({ menuPaths, menuNames: allMenus.map(menu => menu.menuName).filter(Boolean), roleCodes });
      userMenus.value = menuPaths;
      // filteroutputonelevelMenu (parentId === 0)
      let topMenus = allMenus.filter(m =>
        m.parentId === 0 &&
        m.menuCode !== 'ai-analysis' && m.menuName !== 'AI Analysis' && m.menuName !== 'AI分析'
      );



      // monitoringcenterYeshas Sign Inuser systemoneworkasentry. datadatabaseincreaselevelbefore alsoraiseprovidethis entry,
      // dataPermissionstillbyafter endPatientownershipvalidateresponsible for.
      const monitoringMenu = allMenus.find(m => m.menuPath === '/monitoring');
      if (!topMenus.some(m => m.menuPath === '/monitoring')) {
        topMenus.push(monitoringMenu || {
          id: -28,
          parentId: 0,
          menuName: 'Health Overview',
          menuCode: 'monitoring',
          menuPath: '/monitoring',
          sortOrder: 1
        });
      }
      topMenus.sort((a, b) => (a.sortOrder ?? a.orderNum ?? 0) - (b.sortOrder ?? b.orderNum ?? 0));


      // pointawaySystem AdministrationMenu, placeto avatardown pullin
      const sysIdx = topMenus.findIndex(m => m.menuCode === 'system' || m.menuName === 'System Administration' || m.menuName === '系统管理');
      if (sysIdx !== -1) {
        const sys = topMenus[sysIdx];
        const sysNav = resolveNavIcon({ menuCode: sys.menuCode, menuPath: sys.menuPath });
        systemMenu.value = {
          path: sys.menuPath,
          label: 'System Administration',
          icon: sysNav.icon,
          iconTheme: sysNav.theme,
          children: allMenus
            .filter(m => m.parentId === sys.id && m.menuPath)
            .map(child => {
              const c = resolveNavIcon(child);
              return {
                path: child.menuPath,
                label: getEnglishMenuLabel(child.menuPath, child.menuName, child.menuCode),
                icon: c.icon,
                iconTheme: c.theme
              };
            })
        };
        systemMenu.value.entryPath = systemMenu.value.children[0]?.path || sys.menuPath;
        topMenus.splice(sysIdx, 1);
      }

      const builtNavItems = topMenus
        .filter(menu => menu.menuPath || allMenus.some(m => m.parentId === menu.id && m.menuPath))
        .map(menu => {
          const nav = resolveNavIcon(menu);
          return {
            path: menu.menuPath,
            label: getEnglishMenuLabel(menu.menuPath, menu.menuName, menu.menuCode),
            menuCode: menu.menuCode,
            icon: nav.icon,
            iconTheme: nav.theme,
            children: [
              ...(allMenus
                .filter(m => m.parentId === menu.id && m.menuPath)
                .map(child => {
                  const c = resolveNavIcon(child);
                  return {
                    path: child.menuPath,
                    label: getEnglishMenuLabel(child.menuPath, child.menuName, child.menuCode),
                    menuCode: child.menuCode,
                    icon: c.icon,
                    iconTheme: c.theme
                  };
                })),
              ...(menu._children || []).filter(c => c.menuPath || c.path).map(c => {
                const sub = resolveNavIcon(c);
                return {
                  path: c.menuPath || c.path,
                  label: getEnglishMenuLabel(c.menuPath || c.path, c.menuName || c.label, c.menuCode),
                  menuCode: c.menuCode,
                  icon: sub.icon,
                  iconTheme: sub.theme
                };
              })
            ]
          };
        });

      rawNavItems.value = normalizeWorkspaceMenus(builtNavItems, {
        menuPaths: userMenus.value,
        roleCodes
      });
      navFreshRequired = false;
      // firsttimeslazyloadnavigationcompletebefore , route stillcan canYestemporarytime  /, cannotdatathisheavysettoward.
      const loadedIdentity = localStorage.getItem('userId');
      await router.isReady();
      if (!isCurrent() || loadedIdentity !== localStorage.getItem('userId')) return;
      const currentPath = route.fullPath || route.path;
      const authorizedEntry = resolveWorkspaceEntry(currentPath, menuPaths, roleCodes);
      if (!route.meta.hideNav && authorizedEntry !== currentPath) router.replace(authorizedEntry || '/monitoring');
      await loadPatientList();
      if (!isCurrent()) return;
      await refreshSpecialtyScope();
      void checkAccessContext();
    } else if (res.code === 401) {
      resetNavState();
      router.replace('/login');
    } else {
      throw new Error('The feature menu is temporarily unavailable.');
    }
  } catch (error) {
    if (!isCurrent()) return;
    console.error('Failed to load the user menu:', error);
    if (!localStorage.getItem('token')) {
      resetNavState();
      return;
    }
    systemMenu.value = null;
    rawNavItems.value = navFreshRequired ? [] : getDefaultMenus();
    menuLoadError.value = !navFreshRequired && readPermissionCache()
      ? 'The feature menu could not be refreshed. Showing the permissions verified for this session.'
      : 'The feature menu could not be loaded. Please try again.';
  } finally {
    if (isCurrent()) menusLoading.value = false;
  }
};

/** by Path / Codeparsetop barIcon (avoidafter end menuCode missingtimeAlldisplayfilefolder)  */
function resolveNavIcon(menu) {
  const path = (menu.menuPath || menu.path || '').toLowerCase();
  const code = (menu.menuCode || '').toLowerCase();
  const name = (menu.menuName || menu.label || '').toLowerCase();

  // Workspace and Patient
  if (path.includes('/doctor-workspace') || code === 'doctor-workspace') {
    return { icon: 'FirstAidKit', theme: 'medical' };
  }
  if (path.includes('/monitoring') || code === 'monitoring' || code === 'workspace' || name.includes('monitoring') || name.includes('Workspace')) {
    return { icon: 'Monitor', theme: 'monitoring' };
  }
  if (code === 'patient-center' || name.includes('Patientcenter')) {
    return { icon: 'UserFilled', theme: 'patient' };
  }
  if (path.includes('/system/patient') || code === 'system-patient' || code === 'patient-profile' || name.includes('Patientrecord')) {
    return { icon: 'Avatar', theme: 'patient' };
  }
  if (code.includes('care') || code.includes('schedule') || path.includes('/family-health') || name.includes('Care Plan') || name.includes('Dialysis Schedule')) {
    return { icon: 'Calendar', theme: 'schedule' };
  }
  if (path.includes('/settings/notifications') || code.includes('notification') || name.includes('Notification Settings')) {
    return { icon: 'Bell', theme: 'notification' };
  }

  // Dialysismodule: record, trend, AI, Dry Weight
  if (path.includes('/dialysis?tab=data') || code === 'dialysis-record' || name.includes('Dialysis Records') || name.includes('dataentry')) {
    return { icon: 'DocumentChecked', theme: 'dialysis-record' };
  }
  if (path.includes('/dialysis?tab=analysis') || code === 'dialysis-trend' || name.includes('Trend Analysis')) {
    return { icon: 'TrendCharts', theme: 'dialysis-trend' };
  }
  if (path.includes('/dialysis?tab=ai') || code === 'dialysis-ai' || name.includes('ai') && name.includes('analysis')) {
    return { icon: 'Cpu', theme: 'dialysis-ai' };
  }
  if (path.includes('/dry-weight') || code.includes('dry') || name.includes('Dry Weight')) {
    return { icon: 'ScaleToOriginal', theme: 'dryweight' };
  }
  if (path.includes('/dialysis') || code.includes('dialysis') || name.includes('Dialysis Management')) {
    return { icon: 'Histogram', theme: 'dialysis' };
  }

  // clinical records
  if (path.includes('/medical-record?tab=upload') || code.includes('upload') || name.includes('Upload Report')) {
    return { icon: 'Upload', theme: 'medical-upload' };
  }
  if (path.includes('/medical-record?tab=abnormal') || code.includes('abnormal') || name.includes('Abnormal Results')) {
    return { icon: 'WarningFilled', theme: 'medical-warning' };
  }
  if (path.includes('/medical-record?tab=trend') || code.includes('clinical-trend') || name.includes('Result Trends')) {
    return { icon: 'DataLine', theme: 'medical-trend' };
  }
  if (path.includes('/medical-record?tab=list') || code.includes('clinical-record-list') || name.includes('Record List')) {
    return { icon: 'FolderOpened', theme: 'medical-list' };
  }
  if (path.includes('/medical-record') || code.includes('medical') || code.includes('clinical') || name.includes('clinical records') || name.includes('Medical Records')) {
    return { icon: 'FolderOpened', theme: 'medical' };
  }

  // Medication Management
  if (path.includes('/medication?tab=upload') || code.includes('medication-upload') || name.includes('Upload and Recognize')) {
    return { icon: 'Camera', theme: 'medication-upload' };
  }
  if (path.includes('/medication?tab=category') || code.includes('medication-category') || name.includes('Browse Categories')) {
    return { icon: 'Grid', theme: 'medication-category' };
  }
  if (path.includes('/medication?tab=drugs') || code.includes('catalog') || name.includes('Medication List')) {
    return { icon: 'Box', theme: 'medication-catalog' };
  }
  if (path.includes('/medication?tab=logs') || code.includes('medication-log') || name.includes('Medication Log')) {
    return { icon: 'Notebook', theme: 'medication-log' };
  }
  if (path.includes('/medication?tab=remind') || code.includes('reminder') || name.includes('Medication Reminders')) {
    return { icon: 'Bell', theme: 'medication-reminder' };
  }
  if (path.includes('/medication') || code.includes('medication') || name.includes('Medication Management') || name.includes('Medicationmanagement')) {
    return { icon: 'FirstAidKit', theme: 'medication' };
  }

  // healthmonitoring and analysisReport
  if (path.includes('/bp-self-monitor') || code.includes('bp-self-monitor') || name.includes('Blood Pressure & Glucose') || name.includes('Blood GlucoseBlood Pressure')) {
    return { icon: 'Odometer', theme: 'vitals' };
  }
  if (path.includes('/health-analysis?tab=complication') || code.includes('complication') || name.includes('complication')) {
    return { icon: 'Warning', theme: 'complication' };
  }
  if (path.includes('/health-analysis?tab=alert') || code.includes('alert') || name.includes('Health Alerts')) {
    return { icon: 'Bell', theme: 'alert' };
  }
  if (path.includes('/health-analysis?tab=nutrition-assessment') || code.includes('nutrition-assessment') || name.includes('Nutrition Assessment')) {
    return { icon: 'DataAnalysis', theme: 'nutrition-assessment' };
  }
  if (path.includes('/health-analysis?tab=nutrition') || code.includes('nutrition') || name.includes('Nutrition Diary')) {
    return { icon: 'Apple', theme: 'nutrition' };
  }
  if (path.includes('/health-analysis?tab=bp-pattern') || code.includes('bp-pattern') || name.includes('Blood Pressuremode')) {
    return { icon: 'TrendCharts', theme: 'bp-pattern' };
  }
  if (path.includes('/health-analysis?tab=health-report') || code.includes('health-report') || name.includes('Health Report')) {
    return { icon: 'DocumentChecked', theme: 'health-report' };
  }
  if (path.includes('/health-analysis?tab=data-export') || code.includes('data-export') || name.includes('Data Export')) {
    return { icon: 'Download', theme: 'data-export' };
  }
  if (code.includes('health-monitoring') || name.includes('healthmonitoring')) {
    return { icon: 'Monitor', theme: 'health-monitoring' };
  }
  if (code.includes('analysis-report') || name.includes('analysis and Report') || name.includes('Health Analytics')) {
    return { icon: 'DataAnalysis', theme: 'analysis-report' };
  }

  // System Administration
  if (path.includes('/system/user') || code === 'system-user') {
    return { icon: 'User', theme: 'system-user' };
  }
  if (path.includes('/system/role') || code === 'system-role') {
    return { icon: 'Avatar', theme: 'system-role' };
  }
  if (path.includes('/system/menu') || code === 'system-menu') {
    return { icon: 'Menu', theme: 'system-menu' };
  }
  if (path.includes('/system/audit') || code === 'system-audit') {
    return { icon: 'Document', theme: 'system-audit' };
  }
  if (path.includes('/system') || code === 'system') {
    return { icon: 'Setting', theme: 'system' };
  }
  if (path.includes('/dashboard') || code === 'dashboard' || name.includes('firstpage')) {
    return { icon: 'DataBoard', theme: 'dashboard' };
  }
  return { icon: 'Menu', theme: 'default' };
}

// MenuFailed to loadtimestillkeepalready has Permissiondown  featureentry.
function getDefaultMenus() {
  const { menuPaths = [], roleCodes = [] } = readPermissionCache() || {};
  userMenus.value = menuPaths;
  return getFallbackWorkspaceMenus(menuPaths, roleCodes);
}

function isActive(path) {
  if (!path) return false;
  const target = router.resolve(path);
  return target.path === route.path && (!target.query.tab || target.query.tab === route.query.tab);
}

function isItemActive(item) {
  if (isActive(item.path)) return true;
  if (item.children) {
    return item.children.some(child => isActive(child.path));
  }
  return false;
}

// loadPatientlist
async function loadPatientList() {
  const session = captureAuthSession();
  const identity = localStorage.getItem('userId');
  const epoch = ++patientRequestEpoch;
  try {
    const res = await getPatientNames();
    if (epoch !== patientRequestEpoch || !isAuthSessionCurrent(session) || identity !== localStorage.getItem('userId')) return;
    if (res.code === 200) {
      appPatientList.value = res.data || [];
      // A deep locator owns its patient; names bootstrap must not change that context.
      if (!isNarrowCareRoute(route.fullPath)) setPatientList(appPatientList.value);

    }
  } catch (e) {
    console.error('Failed to load the patient list.', e);
  }
}

// switchPatient
let patientSwitchToast = null;
let patientSwitchNoticeEpoch = 0;
function closePatientSwitchToast() {
  patientSwitchNoticeEpoch++;
  patientSwitchToast?.close();
  patientSwitchToast = null;
}
function switchPatient(id) {
  id = id || null;
  if (currentPatientId.value === id) return;
  const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);if(event.defaultPrevented)return;
  currentPatientId.value = id;
  // Invalidate report work at the accepted selection boundary, before Router can
  // coalesce a same-tick A→B→A replacement back to the already-current URL.
  window.dispatchEvent(new Event('care-report-selection-changed'));
  closePatientSwitchToast();
  const noticeEpoch = patientSwitchNoticeEpoch;
  const patients = route.path === '/care' ? patientList.value : appPatientList.value;
  const patient = patients.find(p => p.id === id);
  // The selected identity can wrap the toolbar. Measure only after Vue updates it.
  nextTick(() => {
    if (noticeEpoch !== patientSwitchNoticeEpoch || currentPatientId.value !== id || searchVisible.value) return;
    const toolbarBottom = document.querySelector?.('.workspace-topbar')?.getBoundingClientRect().bottom || 64;
    const noticeOptions = {
      customClass: 'patient-switch-toast',
      offset: Math.max(16, Math.ceil(toolbarBottom) + 12),
      duration: 2500,
      showClose: true
    };
    if (id) {
      patientSwitchToast = ElMessage.success({ ...noticeOptions, message: `Switched to ${patient?.patientName || patient?.name || 'patient'} · ID ${id}.` });
    } else {
      patientSwitchToast = ElMessage.info({ ...noticeOptions, message: 'Showing all patients.' });
    }
  });
  // CareCenter watches the patient itself; preserve its selected tab and filters.
  if (parseReportRoute(route.fullPath)) router.replace(id ? `/care-plans/reports?patientId=${id}` : '/care');
  if (route.path !== '/care' && !isNarrowCareRoute(route.fullPath)) {
    routerViewKey.value = getAuthSessionKey() + '-' + (id || 'all') + '-' + Date.now();
  }
}

// Sign Out
const handleLogout = () => {
  logout();
  resetNavState();
  ElMessage.success('Signed out successfully.');
  router.replace('/login');
};

function openPasswordDialog() {
  accountVisible.value = false;
  Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmPassword: '' });
  passwordVisible.value = true;
}

async function submitPasswordChange() {
  if (!passwordForm.currentPassword || !passwordForm.newPassword) return ElMessage.warning('Enter both your current and new passwords.');
  if (passwordForm.newPassword !== passwordForm.confirmPassword) return ElMessage.warning('The new passwords do not match.');
  if (passwordForm.newPassword.length < 10 || !/[A-Za-z]/.test(passwordForm.newPassword)
      || !/\d/.test(passwordForm.newPassword) || !/[^A-Za-z0-9\s]/.test(passwordForm.newPassword)) {
    return ElMessage.warning('The new password does not meet the security requirements.');
  }
  passwordChanging.value = true;
  try {
    const res = await changePassword({ currentPassword: passwordForm.currentPassword, newPassword: passwordForm.newPassword });
    if (res.code !== 200) return;
    passwordVisible.value = false;
    ElMessage.success('Password changed. Please sign in again.');
    handleLogout();
  } finally {
    passwordChanging.value = false;
  }
}

watch(() => isNarrowCareRoute(route.fullPath), (narrow, previous) => { if (!narrow && previous) setPatientList(appPatientList.value); }, { flush: 'sync' });
watch([currentPatientId, () => route.fullPath], refreshSpecialtyScope, { immediate: true, flush: 'sync' });
watch(currentPatientId, () => {
  if (route.path !== '/care' && !isNarrowCareRoute(route.fullPath)) routerViewKey.value = getAuthSessionKey() + '-patient-' + (++privacyViewRevision);
}, { flush: 'sync' });

watch(
  () => route.path,
  async (path, oldPath) => {
    mobileNavVisible.value = false;
    if (path === '/login') {
      if (!localStorage.getItem('token')) {
        resetNavState();
      }
      return;
    }
    if (localStorage.getItem('token') && (oldPath === '/login' || !navItems.value.length)) {
      await loadUserMenus();
    }
  }
);

onMounted(async () => {
  if (route.path !== '/login' && localStorage.getItem('token')) {
    await loadUserMenus();
  }
});
</script>

<style>
* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
  padding: 0;
}

button,
input,
textarea,
select {
  font: inherit;
}
</style>

<style scoped>.workspace-search-results .is-search-active{background:var(--care-50);outline:2px solid var(--care-600);outline-offset:-2px}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap}</style>

<style>
.patient-switch-toast { max-width: calc(100vw - 24px); }
.patient-switch-toast .el-message__content { min-width: 0; overflow-wrap: anywhere; line-height: 1.5; }
/* A superseded patient must not remain visible during the old notice's exit. */
.patient-switch-toast.el-message-fade-leave-active { display: none; transition: none; }
</style>

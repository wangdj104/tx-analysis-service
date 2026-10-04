import { computed, reactive, watch, onScopeDispose } from 'vue'
import { parseEvidenceRoute } from '../utils/workspaceAccess.js'
import { captureAuthSession, isAuthSessionCurrent, AUTH_STORAGE_KEYS } from '../utils/authSession.js'

/** A report locator never bootstraps a patient profile or a module list. */
export function useFocusedCareSource(route, currentPatientId, fetchSource, sourceType) {
  const locator = computed(() => { const parsed = parseEvidenceRoute(route.fullPath || ''); return !sourceType || parsed?.sourceType === sourceType ? parsed : null })
  const attempted = computed(() => {
    const query = new URLSearchParams((route.fullPath || '').split('?')[1] || '')
    return query.has('measurementId') || query.has('recordId')
  })
  const state = reactive({ record: null, loading: false, error: false })
  let epoch = 0, controller = null, disposed = false
  function clear() { epoch++; controller?.abort(); controller=null; state.record=null; state.loading=false; state.error=false }
  async function reload() {
    clear()
    if (disposed || !attempted.value) return
    const source = locator.value
    if (!source) { state.error=true; return }
    const generation=epoch, path=route.fullPath, auth=captureAuthSession(), actorId=localStorage.getItem('userId')
    const owns=()=>!disposed&&generation===epoch&&path===route.fullPath&&isAuthSessionCurrent(auth)&&actorId===localStorage.getItem('userId')
    if (!auth.token) { state.error=true; return }
    controller=new AbortController();state.loading=true
    try {
      const response=await fetchSource({...source},{expectedAuth:{...auth,actorId},signal:controller.signal})
      if(!owns())return
      const rows=Array.isArray(response.data)?response.data:[response.data], record=rows[0]
      if ((response.code && response.code!==200) || rows.length!==1 || Number(record?.id)!==source.sourceId || Number(record?.patientId??record?.patient_id)!==source.patientId) throw new Error('Source unavailable')
      state.record=record
      if(!owns())return
      state.error=false
    } catch {
      if(owns()){state.record=null;if(owns())state.error=true}
    } finally {if(owns()){state.loading=false;controller=null}}
  }
  function contextChanged(){clear();if(attempted.value)state.error=true}
  function storageChanged(event){if(event.key==null||AUTH_STORAGE_KEYS.includes(event.key))contextChanged()}
  watch(()=>route.fullPath,reload,{immediate:true,flush:'sync'})
  watch(currentPatientId,contextChanged,{flush:'sync'})
  if(typeof window!=='undefined'){window.addEventListener('auth-session-cleared',contextChanged);window.addEventListener('storage',storageChanged)}
  onScopeDispose(()=>{disposed=true;clear();if(typeof window!=='undefined'){window.removeEventListener('auth-session-cleared',contextChanged);window.removeEventListener('storage',storageChanged)}})
  return {locator,attempted,state,reload}
}

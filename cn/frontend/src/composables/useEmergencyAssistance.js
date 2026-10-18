import * as Vue from 'vue';
import * as assistanceApi from '@/api/emergencyAssistance';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { createHouseholdCommandSession } from '@/utils/householdCommandSession';
const copy = value => value == null ? value : JSON.parse(JSON.stringify(value));
const unwrap = response => {
    if (response?.code && response.code !== 200) throw Object.assign(new Error('REQUEST_REJECTED'), {
        status: response.code, data: response.data }
    );
    return response && Object.hasOwn(response, 'data') ? response.data : response;
}
;
const status = error => Number(error?.response?.status ?? error?.status ?? error?.code) || 0;
/** One shared technical command session, with domain-specific drafts and current-authority projections. */
export function useEmergencyAssistance(patientRef, deps = {
}
) {
    const {
        reactive, unref, watch, getCurrentScope, onScopeDispose }
    = Vue;
    const api = deps.api || assistanceApi, host = deps.events || globalThis.window;
    const patient = () => Number(unref(patientRef)) || null;
    const captureAuth = deps.captureAuth || (() => ({
        ...captureAuthSession(), actorId: localStorage.getItem('userId') }
    ));
    const isAuthCurrent = deps.isAuthCurrent || (auth => isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId'));
    const newKey = deps.newKey || (() => {
        if (globalThis.crypto.randomUUID) return globalThis.crypto.randomUUID();
        const b = globalThis.crypto.getRandomValues(new Uint8Array(16));
        b[6] = (b[6] & 15) | 64;
        b[8] = (b[8] & 63) | 128;
        const h = [...b].map(n => n.toString(16).padStart(2,
        '0')).join('');
        return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
    }
    );
    const initial = () => ({
        capabilities: null, items: [], nextCursor: null, sources: [], sourceCursor: null, detail: null,
        events: [],
        eventCursor: null, deliveries: [], deliveryCursor: null, draft: null, operation: null, phase: 'idle',
        error: null, loading: false, dialog: false, reviewedWinner: null, deliveryPhase: 'idle', deliveryRetryId: null, deliveryReviewing: false, legacyItems: [],
        legacyDetail: null, legacyLocation: '', legacyPhase: 'idle' }
    );
    const state = reactive(initial());
    let session = create(), disposed = false, deliveryAttempt = null, deliveryRequest = null, deliveryReview = null, loadingRequest = null,
    selectedEmergency = null;
    const controllers = new Set();
    function create() {
        return createHouseholdCommandSession({
            patient, captureAuth, isAuthCurrent, events: host, newKey }
        );
    }
    function clear() {
        session.dispose();
        for (const c of controllers) c.abort();
        controllers.clear();
        deliveryAttempt = null;
        deliveryRequest = null;
        deliveryReview = null;
        selectedEmergency = null;
        loadingRequest = null;
        Object.assign(state, initial());
        if (!disposed) session = create();
    }
    function fail(error) {
        if ([401,403].includes(status(error))) {
            clear();
            state.phase = 'forbidden';
            state.error = 'ACCESS_DENIED';
            host?.dispatchEvent?.(new Event('emergency-access-denied'));
        }
        else state.error = error?.response?.data?.data?.errorCode || error?.data?.errorCode || 'REQUEST_FAILED';
    }
    async function read(lane, fetch, apply) {
        if (disposed || !patient()) return {
            phase: 'unavailable' }
        ;
        const origin = session, context = origin.capture(lane), controller = new AbortController();
        controllers.add(controller);
        const options = {
            expectedAuth: context.auth, signal: controller.signal, patientId: patient() }
        ;
        try {
            const data = unwrap(await fetch(options));
            if (origin !== session || !origin.owns(context) || controller.signal.aborted) return {
                phase: 'discarded' }
            ;
            apply(data);
            return {
                phase: 'succeeded' }
            ;
        }
        catch (error) {
            if (origin !== session || !origin.owns(context) || controller.signal.aborted) return {
                phase: 'discarded' }
            ;
            fail(error);
            return {
                phase: state.phase === 'forbidden' ? 'forbidden' : 'unavailable' }
            ;
        }
        finally {
            controllers.delete(controller);
        }
    }
    async function load(emergencyEventId = null) {
        // A typed locator reads its fixed detail first. Broad list discovery is a separate user action.
        const target = emergencyEventId == null ? null : Number(emergencyEventId), detail = target ? open(target) : null;
        const origin = session, request = {
        }
        ;
        loadingRequest = request;
        state.loading = true;
        try {
            if (detail) {
                const result = await detail;
                if (origin !== session || loadingRequest !== request || result.phase !== 'succeeded') return result;
                return await read('capabilities', options => api.capabilities(patient(), options), data => {
                    state.capabilities = data;
                }
                );
            }
            const result = await read('capabilities', options => api.capabilities(patient(), options),
            data => {
                state.capabilities = data;
            }
            );
            if (origin === session && loadingRequest === request && result.phase === 'succeeded' && state.capabilities?.read) await loadList();
            if (origin === session && loadingRequest === request && result.phase === 'succeeded' && state.capabilities?.legacyAvailable) await loadLegacyList();
            return result;
        }
        finally {
            if (origin === session && loadingRequest === request) {
                state.loading = false;
                loadingRequest = null;
            }
        }
    }
    function loadList(more = false) {
        const cursor = more ? state.nextCursor : null;
        if (more && !cursor) return Promise.resolve({
            phase: 'unavailable' }
        );
        return read('list', options => api.list({
            patientId: patient(), limit: 30, ...(cursor ? {
                cursor }
            : {
            }
            ) }
        , options), page => {
            state.items = cursor ? [...state.items, ...page.items] : page.items;
            state.nextCursor = page.nextCursor;
        }
        );
    }
    function resetDelivery() {
        deliveryRequest = null;
        deliveryAttempt = null;
        state.deliveryPhase = 'idle';
        state.deliveryRetryId = null;
        deliveryReview = null;
        state.deliveryReviewing = false;
    }
    async function open(id, preserveDraft = false) {
        id = Number(id);
        if (!Number.isSafeInteger(id) || id < 1) return {
            phase: 'unavailable' }
        ;
        // Locator changes are navigation ownership changes. Deliberate close/reopen of the same locator is not.
        if (selectedEmergency != null && selectedEmergency !== id) clear();
        selectedEmergency = id;
        if (!preserveDraft) {
            session.close();
            state.draft = null;
            state.dialog = false;
        }
        state.detail = null;
        state.events = [];
        state.deliveries = [];
        state.eventCursor = null;
        state.deliveryCursor = null;
        resetDelivery();
        return read('detail', options => api.detail(id, options), data => {
            if (Number(data?.patientId) !== patient()) {
                clear();
                state.error = 'PATIENT_MISMATCH';
                return;
            }
            state.detail = data;
        }
        );
    }
    function loadEvents(more = false) {
        if (!state.detail) return Promise.resolve({
            phase: 'unavailable' }
        );
        const id = state.detail.emergencyEventId, cursor = more ? state.eventCursor : null;
        if (more && !cursor) return Promise.resolve({
            phase: 'unavailable' }
        );
        return read('events', options => api.events(id, {
            limit: 30, ...(cursor ? {
                cursor }
            : {
            }
            ) }
        , options), page => {
            if (state.detail?.emergencyEventId !== id) return;
            state.events = cursor ? [...state.events, ...page.items] : page.items;
            state.eventCursor = page.nextCursor;
        }
        );
    }
    function loadDeliveries(more = false) {
        if (!state.detail) return Promise.resolve({
            phase: 'unavailable' }
        );
        const id = state.detail.emergencyEventId, cursor = more ? state.deliveryCursor : null;
        if (more && !cursor) return Promise.resolve({
            phase: 'unavailable' }
        );
        return read('deliveries', options => api.deliveryStatus(id, {
            limit: 30, ...(cursor ? {
                cursor }
            : {
            }
            ) }
        , options), page => {
            if (state.detail?.emergencyEventId !== id) return;
            state.deliveries = cursor ? [...state.deliveries,
            ...page.items] : page.items;
            state.deliveryCursor = page.nextCursor;
        }
        );
    }
    function openDraft(operation, draft = {
    }
    ) {
        if (['unknown','submitting'].includes(state.phase) || !(operation === 'CREATE_REQUEST' ? state.capabilities?.create : state.detail?.allowedCommands?.includes(operation))) return false;
        try {
            session.reset();
        }
        catch {
            return false;
        }
        state.operation = operation;
        state.draft = copy(draft);
        state.dialog = true;
        state.phase = 'idle';
        state.error = null;
        return true;
    }
    async function outcome(origin, result) {
        if (origin !== session || disposed) return {
            phase: 'discarded' }
        ;
        if (result.phase === 'discarded') {
            // Private settlement may release busy/recoverability, but never adopts a closed view's result.
            if (result.settledPhase) {
                state.phase = result.settledPhase;
                state.error = result.errorCode || null;
                if (result.settledPhase === 'succeeded') origin.reset();
            }
            return result;
        }
        state.phase = result.phase;
        state.error = result.errorCode || null;
        if (result.phase === 'forbidden') {
            clear();
            state.phase = 'forbidden';
            state.error = 'ACCESS_DENIED';
            host?.dispatchEvent?.(new Event('emergency-access-denied'));
        }
        else if (result.phase === 'succeeded') {
            state.draft = null;
            state.dialog = false;
            origin.reset();
            if (result.result?.emergencyEventId) await open(result.result.emergencyEventId);
            if (origin === session && state.detail) {
                const index = state.items.findIndex(row => row.emergencyEventId === state.detail.emergencyEventId);
                if (index >= 0) state.items.splice(index,1,copy(state.detail));
            }
        }
        return result;
    }
    // 每次新操作和原样重试先在线读取当前权限；网络不确定包只留在现有内存会话。
    async function current(envelope, identical = false) {
        const data = unwrap(await (envelope.target == null ? api.capabilities(envelope.patientId, {
            expectedAuth: captureAuth() }
        ) : api.detail(envelope.target, {
            patientId: envelope.patientId, expectedAuth: captureAuth() }
        )));
        if (Number(data?.patientId ?? envelope.patientId) !== patient()) throw {
            status: 403 }
        ;
        if (data?.read === false || data?.write === false || (!identical && (envelope.target == null ? !data?.create : !data?.allowedCommands?.includes(envelope.operation)))) throw {
            status: 403 }
        ;
        return data;
    }
    const send = envelope => api.command(envelope, {
        expectedAuth: captureAuth() }
    ).then(unwrap);
    async function submit(bodyOverride = null) {
        if (!state.draft || !state.operation || ['submitting','unknown','invalid','conflict'].includes(state.phase)) return {
            phase: 'unavailable' }
        ;
        const origin = session, target = state.operation === 'CREATE_REQUEST' ? null : state.detail?.emergencyEventId,
        context = origin.capture('command-admission');
        if (!target && state.operation !== 'CREATE_REQUEST') return {
            phase: 'unavailable' }
        ;
        const operation = state.operation, body = copy(bodyOverride || state.draft);
        state.phase = 'submitting';
        try {
            const fresh = await current({
                target, operation, patientId: patient()}
            );
            if (origin !== session || !origin.owns(context)) return {
                phase: 'discarded' }
            ;
            origin.begin(operation,target,{
                patientId:patient(),expectedVersion:target == null ? 0 : fresh.version}
            ,body);
            return await outcome(origin,await origin.submit(send));
        }
        catch (error) {
            if (origin !== session || !origin.owns(context)) return {
                phase: 'discarded' }
            ;
            fail(error);
            if(state.phase!=='forbidden')state.phase='idle';
            return {
                phase: state.phase==='forbidden'?'forbidden':'unavailable' }
            ;
        }
    }
    async function retry() {
        const origin=session,previous=state.phase;
        try {
            state.phase='submitting';
            return await outcome(origin,await origin.retryIdentical(async envelope=>{
                try {
                    await current(envelope,true);
                }
                catch(error){
                    if(origin===session)fail(error);
                    throw error;
                }
                if(origin!==session)throw {
                    status:403}
                ;
                return send(envelope);
            }
            ));
        }
        catch {
            if(origin===session)state.phase=previous;
            return {
                phase:origin===session?'unavailable':'discarded'}
            ;
        }
    }
    async function reconcile() {
        const origin = session;
        try {
            return await outcome(origin, await origin.reconcile(envelope => api.receipt(envelope.patientId,
            envelope.commandKey,
            {
                expectedAuth: captureAuth() }
            ).then(unwrap)));
        }
        catch {
            return {
                phase: origin === session ? 'unavailable' : 'discarded' }
            ;
        }
    }
    function editAfterError() {
        if (!['invalid','conflict'].includes(state.phase)) return false;
        session.reset();
        state.phase = 'idle';
        state.error = null;
        return true;
    }
    function close() {
        session.close();
        state.dialog = false;
        state.draft = null;
        state.operation = null;
        state.detail = null;
        state.events = [];
        state.deliveries = [];
        state.reviewedWinner = null;
        resetDelivery();
    }
    async function reviewDeliveries() {
        if (state.deliveryPhase === 'submitting' || state.deliveryReviewing) return {phase: 'unavailable'};
        const origin = session, selected = selectedEmergency, context = origin.capture('delivery-review');
        const review = {};
        deliveryReview = review;
        state.deliveryReviewing = true;
        const owns = () => deliveryReview === review && origin === session && origin.owns(context) && selected === selectedEmergency;
        try {
            const result = await loadDeliveries();
            // Close/导航立即撤销旧请求所有权；旧完成不能释放新review或清除新的未知重试。
            if (!owns()) return {phase: 'discarded'};
            // 明确刷新只读取状态。新的token/重复风险须由用户重新决定，不能自动再发送。
            if (result.phase === 'succeeded') resetDelivery();
            return result;
        } finally {
            if (deliveryReview === review) {
                deliveryReview = null;
                state.deliveryReviewing = false;
            }
        }
    }
    async function retryDelivery(row, acknowledged) {
        if (!state.detail || !row?.canRetry || !state.deliveries.some(item => item.id === row.id && item.retryToken === row.retryToken)) return {phase: 'unavailable'};
        if (row.requiresDuplicateRiskAcknowledgement && !acknowledged) return {phase: 'acknowledgement-required'};
        if (['conflict', 'invalid'].includes(state.deliveryPhase)) return {phase: 'decision-required'};
        if (state.deliveryPhase === 'submitting' || state.deliveryReviewing) return {phase: 'unavailable'};
        const recovering = state.deliveryPhase === 'unknown' && deliveryAttempt !== null;
        if (recovering && deliveryAttempt.id !== row.id) return {phase: 'unavailable'};
        const origin = session, context = origin.capture('delivery-retry'), selected = selectedEmergency;
        const request = {};
        const attempt = recovering ? deliveryAttempt : Object.freeze({
            id: row.id,
            body: Object.freeze({retryToken: row.retryToken, duplicateRiskAcknowledged: !!acknowledged})
        });
        deliveryRequest = request;
        state.deliveryPhase = 'submitting';
        let submitted = false;
        const owns = () => origin === session && origin.owns(context) && deliveryRequest === request && selectedEmergency === selected;
        try {
            const currentDetail = unwrap(await api.detail(selected, {patientId: patient(), expectedAuth: context.auth}));
            if (!owns()) return {phase: 'discarded'};
            if (Number(currentDetail?.patientId) !== patient() || currentDetail?.read === false || currentDetail?.retry === false) throw {status: 403};
            // 只有实际POST后才存在待核验提交；此前GET失败不制造‘已提交但未知’。
            deliveryAttempt = attempt;
            state.deliveryRetryId = attempt.id;
            submitted = true;
            await api.retryDelivery(attempt.id, attempt.body, {expectedAuth: context.auth});
            if (!owns()) return {phase: 'discarded'};
            state.deliveryPhase = 'succeeded';
            deliveryAttempt = null;
            state.deliveryRetryId = null;
            await loadDeliveries();
            return {phase: 'succeeded'};
        } catch (error) {
            if (!owns()) return {phase: 'discarded'};
            if ([401, 403].includes(status(error))) {
                fail(error);
                return {phase: 'forbidden'};
            }
            if (!submitted) {
                // 原POST已不确定时，恢复GET失败仍保留原样意图；首次GET失败没有POST。
                state.deliveryPhase = recovering ? 'unknown' : 'unavailable';
            } else {
                state.deliveryPhase = status(error) === 409 ? 'conflict' : status(error) === 400 ? 'invalid' : 'unknown';
            }
            if (state.deliveryPhase !== 'unknown') {
                deliveryAttempt = null;
                state.deliveryRetryId = null;
            }
            return {phase: state.deliveryPhase};
        } finally {
            if (deliveryRequest === request) {
                deliveryRequest = null;
                if (state.deliveryPhase === 'submitting') state.deliveryPhase = 'idle';
            }
        }
    }
    function loadLegacyList() {
        return read('legacy-list', options=>api.legacyList(patient(),options), rows=>{
            state.legacyItems=rows||[];
        }
        );
    }
    function openLegacy(id) {
        return read('legacy-detail', options=>api.legacyDetail(id,options), value=>{
            if(Number(value?.patient_id)!==patient()){
                clear();
                return;
            }
            state.legacyDetail=value;
        }
        );
    }
    // A-only入口仍由现有旧路由处理；不确定提交不能变成离线队列或自动重试。
    async function createLegacy() {
        if (!state.capabilities?.legacyAvailable || !state.capabilities?.legacyCanWrite || ['submitting',
        'unknown'].includes(state.legacyPhase)) return {
            phase:'unavailable'}
        ;
        const origin=session,context=origin.capture('legacy-command'),patientId=patient(),locationText=state.legacyLocation;
        state.legacyPhase='submitting';
        try {
            const current=unwrap(await api.capabilities(patientId,{
                expectedAuth:context.auth}
            ));
            if(origin!==session||!origin.owns(context))return {
                phase:'discarded'}
            ;
            if(!current.legacyAvailable||!current.legacyCanWrite)throw {
                status:403}
            ;
        }
        catch(error){
            if(origin!==session||!origin.owns(context))return {
                phase:'discarded'}
            ;
            fail(error);
            if(origin===session)state.legacyPhase='idle';
            return {
                phase:state.phase==='forbidden'?'forbidden':'unavailable'}
            ;
        }
        try {
            await api.legacyCreate({
                patientId,locationText}
            ,{
                expectedAuth:context.auth}
            );
            if(origin!==session||!origin.owns(context))return {
                phase:'discarded'}
            ;
            state.legacyPhase='recorded';
            state.legacyLocation='';
            await loadLegacyList();
            return {
                phase:'succeeded'}
            ;
        }
        catch(error){
            if(origin!==session||!origin.owns(context))return {
                phase:'discarded'}
            ;
            if([401,403].includes(status(error))){
                fail(error);
                return {
                    phase:'forbidden'}
                ;
            }
            state.legacyPhase=status(error)>=400&&status(error)<500?'invalid':'unknown';
            return {
                phase:state.legacyPhase}
            ;
        }
    }
    const onIdentity = () => clear();
    for (const event of ['auth-session-cleared','auth-session-changed','storage']) host?.addEventListener?.(event,
    onIdentity);
    const stop = watch(patientRef, clear, {
        flush: 'sync' }
    );
    function dispose() {
        disposed = true;
        stop();
        clear();
        for (const event of ['auth-session-cleared','auth-session-changed','storage']) host?.removeEventListener?.(event,
        onIdentity);
    }
    if (getCurrentScope()) onScopeDispose(dispose);
    return {
        state, invalidate: clear, load, loadList, open, loadEvents, loadDeliveries, openDraft, submit,
        retry,
        reconcile, editAfterError, close, retryDelivery, reviewDeliveries, loadLegacyList, openLegacy, createLegacy, dispose }
    ;
}

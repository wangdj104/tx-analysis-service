import { onMounted, onUnmounted, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
/** Preserve the in-memory original envelope until its outcome is known. Auth loss still clears it. */
export function useAlertWorkflowNavigation(flow) {
    const unresolved = () => [flow.state.commandPhase, flow.state.sourcePhase, flow.state.deliveryPhase].some(phase => ['submitting', 'uncertain'].includes(phase));
    function guard() {
        flow.state.navigationBlocked = unresolved();
        return !flow.state.navigationBlocked;
    }
    function contextChange(event) {
        if (!event.defaultPrevented && !guard()) {
            event.preventDefault();
            event.stopImmediatePropagation();
        }
    }
    function beforeUnload(event) {
        if (unresolved()) {
            event.preventDefault();
            event.returnValue = '';
        }
    }
    onBeforeRouteLeave(guard);
    onBeforeRouteUpdate(guard);
    watch(unresolved, value => {
        if (!value)
            flow.state.navigationBlocked = false;
    }, { flush: 'sync' });
    onMounted(() => {
        window.addEventListener('care-plan-before-context-change', contextChange);
        window.addEventListener('beforeunload', beforeUnload);
    });
    onUnmounted(() => {
        window.removeEventListener('care-plan-before-context-change', contextChange);
        window.removeEventListener('beforeunload', beforeUnload);
    });
    return { guard };
}

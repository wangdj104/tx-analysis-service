import { onScopeDispose, readonly, ref } from 'vue';

// Presentation preference only. Each consumer releases its media subscription.
export function useReducedMotion() {
  const media = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  const reducedMotion = ref(Boolean(media?.matches));
  const update = event => { reducedMotion.value = event.matches; };
  if (media?.addEventListener) {
    media.addEventListener('change', update);
    onScopeDispose(() => media.removeEventListener('change', update));
  } else if (media?.addListener) {
    media.addListener(update);
    onScopeDispose(() => media.removeListener(update));
  }
  return readonly(reducedMotion);
}

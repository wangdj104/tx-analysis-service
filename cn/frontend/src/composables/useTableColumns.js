import { ref, watch } from 'vue';

/**
 * tablegridcolumndisplayconfiguration, persistto  localStorage
 * @param {string} storageKey onlyonekey, for example  medical-record-list
 * @param {Array<{key:string,label:string,default?:boolean}>} columns
 */
export function useTableColumns(storageKey, columns) {
  const defaultKeys = columns.filter(c => c.default !== false).map(c => c.key);

  function load() {
    try {
      const raw = localStorage.getItem(`table-cols:${storageKey}`);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed) && parsed.length > 0) {
          return parsed.filter(k => columns.some(c => c.key === k));
        }
      }
    } catch {
      /* ignore */
    }
    return [...defaultKeys];
  }

  const visibleKeys = ref(load());

  watch(visibleKeys, (val) => {
    try {
      localStorage.setItem(`table-cols:${storageKey}`, JSON.stringify(val));
    } catch {
      /* ignore */
    }
  }, { deep: true });

  function isVisible(key) {
    return visibleKeys.value.includes(key);
  }

  function resetColumns() {
    visibleKeys.value = [...defaultKeys];
  }

  return { visibleKeys, isVisible, resetColumns, columnDefs: columns };
}

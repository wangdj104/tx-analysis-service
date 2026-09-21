import { ref, computed, watch } from 'vue';

const STORAGE_KEY = 'currentPatientId';
const EVENT_KEY = 'current-patient-change';

const patientId = ref(null);
const patientList = ref([]);

function loadFromStorage() {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (raw) {
    const id = Number(raw);
    patientId.value = isNaN(id) ? null : id;
  } else {
    patientId.value = null;
  }
}

loadFromStorage();
window.addEventListener('auth-session-cleared', () => {
  patientId.value = null;
  patientList.value = [];
});

window.addEventListener('storage', (e) => {
  if (e.key === STORAGE_KEY) {
    const id = e.newValue ? Number(e.newValue) : null;
    patientId.value = isNaN(id) ? null : id;
  }
});

export function useCurrentPatient() {
  const currentPatientId = computed({
    get: () => patientId.value,
    set: (val) => {
      const id = val ? Number(val) : null;
      patientId.value = id;
      if (id) {
        localStorage.setItem(STORAGE_KEY, String(id));
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
      window.dispatchEvent(new CustomEvent(EVENT_KEY, { detail: { patientId: id } }));
    }
  });

  const currentPatient = computed(() => {
    if (!patientId.value) return null;
    return patientList.value.find(p => p.id === patientId.value) || null;
  });

  const currentPatientName = computed(() => {
    return currentPatient.value?.patientName || currentPatient.value?.name || '';
  });

  function setPatientList(list) {
    patientList.value = list || [];
    const selectedExists = patientId.value != null && patientList.value.some(p => Number(p.id) === Number(patientId.value));
    if (!patientList.value.length) {
      if (patientId.value != null) currentPatientId.value = null;
      return;
    }
    // firsttimesenterDefaultselectNo. one; up timesselectstillhas validtimecontinuekeep.
    if (!selectedExists) currentPatientId.value = patientList.value[0].id;
  }

  function clearCurrentPatient() {
    currentPatientId.value = null;
  }

  return {
    currentPatientId,
    currentPatient,
    currentPatientName,
    patientList,
    setPatientList,
    clearCurrentPatient
  };
}

export function getCurrentPatientIdFromStorage() {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) return null;
  const id = Number(raw);
  return isNaN(id) ? null : id;
}

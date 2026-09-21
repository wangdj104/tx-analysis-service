import { ref, inject, watch } from 'vue';

/**
 * MenuPermissiondetermine composable
 * - hasMenu(path): ExaminationuserYesNohas somePath MenuPermission (for example  hasMenu('/dialysis'))
 * - hasMenuName(name): ExaminationuserYesNohas someName MenuPermission (for example  hasMenuName('Complication Tracking'))
 * - hasAnyMenuName(names): ExaminationuserYesNohas anyoneName MenuPermission
 * - hasRoleCode(code): ExaminationuserYesNohas someRoleCode (for example  hasRoleCode('diabetes_hypertension'))
 */
export function useMenuPermission() {
  const userMenuPaths = ref([]);
  const userMenuNames = ref([]);
  const userRoleCodes = ref([]);
  const injectedMenus = inject('userMenus', null);

  function syncFromStorage() {
    if (injectedMenus?.value?.length) {
      userMenuPaths.value = injectedMenus.value;
    } else {
      try {
        const raw = localStorage.getItem('userMenus');
        userMenuPaths.value = raw ? JSON.parse(raw) : [];
      } catch { userMenuPaths.value = []; }
    }
    try {
      const rawNames = localStorage.getItem('userMenuNames');
      userMenuNames.value = rawNames ? JSON.parse(rawNames) : [];
    } catch { userMenuNames.value = []; }
    try {
      const rawRoles = localStorage.getItem('userRoleCodes');
      userRoleCodes.value = rawRoles ? JSON.parse(rawRoles) : [];
    } catch { userRoleCodes.value = []; }
  }

  if (injectedMenus) {
    watch(injectedMenus, syncFromStorage, { immediate: true });
  } else {
    syncFromStorage();
  }

  /** ExaminationuserYesNohavehas specifiedPath Menu */
  function hasMenu(path) {
    return userMenuPaths.value.includes(path);
  }

  /** ExaminationuserYesNohavehas specifiedName Menu */
  function hasMenuName(name) {
    return userMenuNames.value.includes(name);
  }

  /** ExaminationuserYesNohavehas anyonespecifiedName Menu */
  function hasAnyMenuName(names) {
    return names.some(n => userMenuNames.value.includes(n));
  }

  /** ExaminationuserYesNohavehas specifiedRoleCode */
  function hasRoleCode(code) {
    return userRoleCodes.value.includes(code);
  }

  return { hasMenu, hasMenuName, hasAnyMenuName, hasRoleCode, syncFromStorage };
}

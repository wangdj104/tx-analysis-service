<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Role Management</h1>
                <p class="subtitle">managementsystemRoleandMenuPermissionconfiguration</p>
              </div>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <div class="toolbar">
            <el-button v-if="isAdmin" type="primary" @click="showAddDialog">
              <el-icon><Plus /></el-icon>AddRole
            </el-button>
          </div>
          <el-table :data="roles" stripe class="app-data-table">
            <el-table-column v-if="roleColVisible('roleCode')" prop="roleCode" label="Code" min-width="108" />
            <el-table-column v-if="roleColVisible('roleName')" prop="roleName" label="Name" min-width="108" />
            <el-table-column v-if="roleColVisible('description')" prop="description" label="Description" min-width="160" show-overflow-tooltip />
            <el-table-column v-if="roleColVisible('status')" prop="status" label="Status" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">Normal</el-tag>
                <el-tag v-else type="danger" size="small">Disabled</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Actions" width="220" align="center" fixed="right">
              <template #header>
                <TableActionHeader v-model="roleVisibleCols" :columns="ROLE_COLUMN_DEFS" @reset="resetRoleColumns" />
              </template>
              <template #default="{ row }">
                <template v-if="isAdmin">
                  <el-button link type="primary" size="small" @click="showEditDialog(row)">Edit</el-button>
                  <el-button link type="primary" size="small" @click="showMenuDialog(row)">configurationMenu</el-button>
                  <el-popconfirm title="Confirm deletion?" @confirm="handleDelete(row.id)">
                    <template #reference>
                      <el-button link type="danger" size="small" :disabled="row.roleCode === 'admin'">Delete</el-button>
                    </template>
                  </el-popconfirm>
                </template>
                <span v-else class="text-gray">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- Add/EditRole -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? 'EditRole' : 'AddRole'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px" :rules="rules" ref="formRef">
        <el-form-item label="RoleCode" prop="roleCode">
          <el-input v-model="form.roleCode" :disabled="isEdit || form.roleCode === 'admin'" placeholder="for example : editor" />
        </el-form-item>
        <el-form-item label="RoleName" prop="roleName">
          <el-input v-model="form.roleName" placeholder="for example : Editmember" />
        </el-form-item>
        <el-form-item label="Description">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="RoleDescription" />
        </el-form-item>
        <el-form-item label="Status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">Normal</el-radio>
            <el-radio :value="0">Disabled</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleSave">Save</el-button>
      </template>
    </el-dialog>

    <!-- configurationMenuPermission -->
    <el-dialog v-model="menuDialogVisible" title="configurationMenuPermission" width="520px" destroy-on-close>
      <el-alert v-if="currentRole?.roleCode === 'admin'" title="adminRole MenuPermissioncannot in pageEdit, for example needadjustPlease Manualrefreshdatabase" type="warning" :closable="false" style="margin-bottom: 16px" />
      <el-tree
        ref="menuTreeRef"
        :data="menuTreeData"
        show-checkbox
        node-key="id"
        :default-expand-all="true"
        :default-checked-keys="checkedMenuIds"
        :props="{ label: 'menuName', children: 'children' }"
        :disabled="currentRole?.roleCode === 'admin'"
      />
      <template #footer>
        <el-button @click="menuDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleAssignMenus" :disabled="currentRole?.roleCode === 'admin'">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { getRoleList, saveRole, deleteRole, getRoleMenus, assignMenus } from '@/api/role.js';
import { getMenuList } from '@/api/menu.js';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';

const ROLE_COLUMN_DEFS = [
  { key: 'roleCode', label: 'Code' },
  { key: 'roleName', label: 'Name' },
  { key: 'description', label: 'Description', default: false },
  { key: 'status', label: 'Status' }
];
const { visibleKeys: roleVisibleCols, isVisible: roleColVisible, resetColumns: resetRoleColumns } =
  useTableColumns('role-list', ROLE_COLUMN_DEFS);

const roles = ref([]);

const isAdmin = computed(() => {
  try {
    const raw = localStorage.getItem('userRoleCodes');
    if (raw) {
      const codes = JSON.parse(raw);
      return codes.includes('admin');
    }
  } catch (e) {
    console.error(e);
  }
  return false;
});
const menus = ref([]);
const menuTreeData = ref([]);
const dialogVisible = ref(false);
const menuDialogVisible = ref(false);
const isEdit = ref(false);
const formRef = ref(null);
const menuTreeRef = ref(null);
const currentRole = ref(null);
const checkedMenuIds = ref([]);

const form = reactive({
  id: null, roleCode: '', roleName: '', description: '', status: 1
});

const rules = {
  roleCode: [{ required: true, message: 'Enter RoleCode', trigger: 'blur' }],
  roleName: [{ required: true, message: 'Enter RoleName', trigger: 'blur' }]
};

async function loadRoles() {
  try {
    const res = await getRoleList();
    if (res.code === 200) roles.value = res.data || [];
  } catch (e) {
    console.error(e);
  }
}

async function loadMenus() {
  try {
    const res = await getMenuList();
    if (res.code === 200) {
      menus.value = res.data || [];
      menuTreeData.value = buildTree(menus.value);
    }
  } catch (e) {
    console.error(e);
  }
}

function isAssignableMenu(item) {
  return item.status !== 0;
}

function buildTree(list) {
  const filtered = list.filter(isAssignableMenu).sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  const map = {};
  const tree = [];
  filtered.forEach(item => {
    map[item.id] = { ...item, children: [] };
  });
  filtered.forEach(item => {
    if (item.parentId && item.parentId !== 0 && map[item.parentId]) {
      map[item.parentId].children.push(map[item.id]);
    } else if (map[item.id]) {
      tree.push(map[item.id]);
    }
  });
  tree.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  tree.forEach(node => {
    if (node.children?.length) {
      node.children.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
    }
  });
  return tree;
}

function showAddDialog() {
  isEdit.value = false;
  Object.assign(form, { id: null, roleCode: '', roleName: '', description: '', status: 1 });
  dialogVisible.value = true;
}

function showEditDialog(row) {
  isEdit.value = true;
  Object.assign(form, { ...row });
  dialogVisible.value = true;
}

async function handleSave() {
  try {
    await formRef.value.validate();
    const res = await saveRole(form);
    if (res.code === 200) {
      ElMessage.success(res.data || 'Saved successfully');
      dialogVisible.value = false;
      loadRoles();
    } else {
      ElMessage.error(res.msg || 'Failed to save');
    }
  } catch (e) {
    console.error(e);
  }
}

async function handleDelete(id) {
  const res = await deleteRole(id);
  if (res.code === 200) {
    ElMessage.success('Deleted successfully');
    loadRoles();
  } else {
    ElMessage.error(res.msg || 'Failed to delete');
  }
}

async function showMenuDialog(row) {
  currentRole.value = row;
  const res = await getRoleMenus(row.id);
  if (res.code === 200) {
    const assignableIds = new Set(
      menus.value.filter(isAssignableMenu).map(m => m.id)
    );
    checkedMenuIds.value = (res.data || []).filter(id => assignableIds.has(id));
  }
  menuDialogVisible.value = true;
}

async function handleAssignMenus() {
  const keys = menuTreeRef.value.getCheckedKeys();
  const halfKeys = menuTreeRef.value.getHalfCheckedKeys();
  const allKeys = [...keys, ...halfKeys];
  const res = await assignMenus({ roleId: currentRole.value.id, menuIds: allKeys });
  if (res.code === 200) {
    ElMessage.success('Menu permissions updated successfully');
    menuDialogVisible.value = false;
  } else {
    ElMessage.error(res.msg || 'Configuration failed');
  }
}

onMounted(() => {
  loadRoles();
  loadMenus();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

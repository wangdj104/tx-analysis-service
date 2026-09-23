<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>User Management</h1>
                <p class="subtitle">managementsystemuserAccountandRoleassign</p>
              </div>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <div class="toolbar">
            <el-button v-if="isAdmin" type="primary" @click="showAddDialog">
              <el-icon><Plus /></el-icon>Adduser
            </el-button>
          </div>
          <el-table :data="users" stripe class="app-data-table">
            <el-table-column v-if="userColVisible('username')" prop="username" label="Username" min-width="108" />
            <el-table-column v-if="userColVisible('realName')" prop="realName" label="Name" min-width="100" />
            <el-table-column v-if="userColVisible('phone')" prop="phone" label="Phone" min-width="120" />
            <el-table-column v-if="userColVisible('email')" prop="email" label="email" min-width="148" show-overflow-tooltip />
            <el-table-column v-if="userColVisible('status')" prop="status" label="Status" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">Normal</el-tag>
                <el-tag v-else type="danger" size="small">Disabled</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Actions" width="260" align="center" fixed="right">
              <template #header>
                <TableActionHeader v-model="userVisibleCols" :columns="USER_COLUMN_DEFS" @reset="resetUserColumns" />
              </template>
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showEditDialog(row)">Edit</el-button>
                <template v-if="isAdmin">
                  <el-button link type="primary" size="small" @click="showRoleDialog(row)">assignRole</el-button>
                  <el-button link type="warning" size="small" @click="showResetDialog(row)">ResetPassword</el-button>
                  <el-popconfirm title="Confirm deletion?" @confirm="handleDelete(row.id)">
                    <template #reference>
                      <el-button link type="danger" size="small" :disabled="row.username === 'admin'">Delete</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- Add/Edituser -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? 'Edituser' : 'Adduser'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px" :rules="rules" ref="formRef">
        <el-form-item label="Username" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="Enter your username" />
        </el-form-item>
        <el-form-item label="realName">
          <el-input v-model="form.realName" placeholder="Enter realName" />
        </el-form-item>
        <el-form-item label="Password" prop="password" v-if="!isEdit">
          <el-input v-model="form.password" type="password" placeholder="to few10, containletters, numbers and special characters" show-password />
        </el-form-item>
        <el-form-item label="Phone Number">
          <el-input v-model="form.phone" placeholder="Enter Phone Number" />
        </el-form-item>
        <el-form-item label="email">
          <el-input v-model="form.email" placeholder="Enter email" />
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

    <!-- assignRole -->
    <el-dialog v-model="roleDialogVisible" title="assignRole" width="400px" destroy-on-close>
      <el-checkbox-group v-model="selectedRoleIds">
        <el-checkbox v-for="role in roles" :key="role.id" :value="role.id">{{ role.roleName }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleAssignRoles">Save</el-button>
      </template>
    </el-dialog>

    <!-- ResetPassword -->
    <el-dialog v-model="resetDialogVisible" title="ResetPassword" width="400px" destroy-on-close>
      <el-form :model="resetForm" label-width="100px">
        <el-form-item label="New Password" required>
          <el-input v-model="resetForm.newPassword" type="password" placeholder="to few10, containletters, numbers and special characters" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleResetPassword">ConfirmReset</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue';
import { ElMessage } from 'element-plus';
import { getUserList, saveUser, updateUser, deleteUser, resetPassword, assignRoles, getUserRoles } from '@/api/user.js';
import { getRoleList } from '@/api/role.js';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';

const USER_COLUMN_DEFS = [
  { key: 'username', label: 'Username' },
  { key: 'realName', label: 'Name' },
  { key: 'phone', label: 'Phone' },
  { key: 'email', label: 'email', default: false },
  { key: 'status', label: 'Status' }
];
const { visibleKeys: userVisibleCols, isVisible: userColVisible, resetColumns: resetUserColumns } =
  useTableColumns('user-list', USER_COLUMN_DEFS);

const users = ref([]);

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
const roles = ref([]);
const dialogVisible = ref(false);
const roleDialogVisible = ref(false);
const resetDialogVisible = ref(false);
const isEdit = ref(false);
const formRef = ref(null);
const currentUserId = ref(null);
const selectedRoleIds = ref([]);

const form = reactive({
  id: null, username: '', realName: '', password: '', phone: '', email: '', status: 1
});

const resetForm = reactive({ id: null, newPassword: '' });

const rules = {
  username: [{ required: true, message: 'Enter your username', trigger: 'blur' }],
  password: [
    { required: true, message: 'Enter your password', trigger: 'blur' },
    { min: 10, message: 'Passwordto few10', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).+$/, message: 'Passwordneedincludeletters, numbers and special characters', trigger: 'blur' }
  ]
};

async function loadUsers() {
  try {
    const res = await getUserList();
    if (res.code === 200) users.value = res.data || [];
  } catch (e) {
    console.error(e);
  }
}

async function loadRoles() {
  try {
    const res = await getRoleList();
    if (res.code === 200) roles.value = (res.data || []).filter(role => !role.roleCode?.startsWith('specialty_'));
  } catch (e) {
    console.error(e);
  }
}

function showAddDialog() {
  isEdit.value = false;
  Object.assign(form, { id: null, username: '', realName: '', password: '', phone: '', email: '', status: 1 });
  dialogVisible.value = true;
}

function showEditDialog(row) {
  isEdit.value = true;
  Object.assign(form, { ...row, password: '' });
  dialogVisible.value = true;
}

async function handleSave() {
  try {
    await formRef.value.validate();
    const res = isEdit.value ? await updateUser(form) : await saveUser(form);
    if (res.code === 200) {
      ElMessage.success(res.data || 'Saved successfully');
      dialogVisible.value = false;
      loadUsers();
    } else {
      ElMessage.error(res.msg || 'Failed to save');
    }
  } catch (e) {
    console.error(e);
  }
}

async function handleDelete(id) {
  const res = await deleteUser(id);
  if (res.code === 200) {
    ElMessage.success('Deleted successfully');
    loadUsers();
  } else {
    ElMessage.error(res.msg || 'Failed to delete');
  }
}

async function showRoleDialog(row) {
  currentUserId.value = row.id;
  const res = await getUserRoles(row.id);
  if (res.code === 200) selectedRoleIds.value = (res.data || []).filter(id => roles.value.some(role => role.id === id));
  roleDialogVisible.value = true;
}

async function handleAssignRoles() {
  const res = await assignRoles({ userId: currentUserId.value, roleIds: selectedRoleIds.value });
  if (res.code === 200) {
    ElMessage.success('Role assigned successfully');
    roleDialogVisible.value = false;
  } else {
    ElMessage.error(res.msg || 'Assignment failed');
  }
}

function showResetDialog(row) {
  resetForm.id = row.id;
  resetForm.newPassword = '';
  resetDialogVisible.value = true;
}

async function handleResetPassword() {
  if (!resetForm.newPassword || resetForm.newPassword.length < 10
      || !/[A-Za-z]/.test(resetForm.newPassword) || !/\d/.test(resetForm.newPassword)
      || !/[^A-Za-z0-9\s]/.test(resetForm.newPassword)) {
    ElMessage.warning('New Passwordto few10, andneedincludeletters, numbers and special characters');
    return;
  }
  const res = await resetPassword(resetForm);
  if (res.code === 200) {
    ElMessage.success('Password reset successfully');
    resetDialogVisible.value = false;
  } else {
    ElMessage.error(res.msg || 'Resetfailed');
  }
}

onMounted(() => {
  loadUsers();
  loadRoles();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

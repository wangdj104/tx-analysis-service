<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>用户管理</h1>
                <p class="subtitle">管理系统用户账号与角色分配。</p>
              </div>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <div class="toolbar">
            <el-button v-if="isAdmin" type="primary" @click="showAddDialog">
              <el-icon><Plus /></el-icon>新增用户
            </el-button>
          </div>
          <el-table :data="users" stripe class="app-data-table">
            <el-table-column v-if="userColVisible('username')" prop="username" label="用户名" min-width="108" />
            <el-table-column v-if="userColVisible('realName')" prop="realName" label="姓名" min-width="100" />
            <el-table-column v-if="userColVisible('phone')" prop="phone" label="电话" min-width="120" />
            <el-table-column v-if="userColVisible('email')" prop="email" label="邮箱" min-width="148" show-overflow-tooltip />
            <el-table-column v-if="userColVisible('status')" prop="status" label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">正常</el-tag>
                <el-tag v-else type="danger" size="small">停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" align="center" fixed="right">
              <template #header>
                <TableActionHeader v-model="userVisibleCols" :columns="USER_COLUMN_DEFS" @reset="resetUserColumns" />
              </template>
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
                <template v-if="isAdmin">
                  <el-button link type="primary" size="small" @click="showRoleDialog(row)">分配角色</el-button>
                  <el-button link type="warning" size="small" @click="showResetDialog(row)">重置密码</el-button>
                  <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                    <template #reference>
                      <el-button link type="danger" size="small" :disabled="row.username === 'admin'">删除</el-button>
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
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px" :rules="rules" ref="formRef">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="真实姓名">
          <el-input v-model="form.realName" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="密码" prop="password" v-if="!isEdit">
          <el-input v-model="form.password" type="password" placeholder="至少 10 位，包含字母、数字和特殊字符" show-password />
        </el-form-item>
        <el-form-item label="手机号码">
          <el-input v-model="form.phone" placeholder="请输入手机号码" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- assignRole -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="400px" destroy-on-close>
      <el-checkbox-group v-model="selectedRoleIds">
        <el-checkbox v-for="role in roles" :key="role.id" :value="role.id">{{ role.roleName }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAssignRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- ResetPassword -->
    <el-dialog v-model="resetDialogVisible" title="重置密码" width="400px" destroy-on-close>
      <el-form :model="resetForm" label-width="100px">
        <el-form-item label="新密码" required>
          <el-input v-model="resetForm.newPassword" type="password" placeholder="至少 10 位，包含字母、数字和特殊字符" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleResetPassword">确认重置</el-button>
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
  { key: 'username', label: '用户名' },
  { key: 'realName', label: '姓名' },
  { key: 'phone', label: '电话' },
  { key: 'email', label: '邮箱', default: false },
  { key: 'status', label: '状态' }
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
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 10, message: '密码至少 10 位', trigger: 'blur' },
    { pattern: /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s]).+$/, message: '密码需包含字母、数字和特殊字符', trigger: 'blur' }
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
      ElMessage.success(res.data || '保存成功');
      dialogVisible.value = false;
      loadUsers();
    } else {
      ElMessage.error(res.msg || '保存失败');
    }
  } catch (e) {
    console.error(e);
  }
}

async function handleDelete(id) {
  const res = await deleteUser(id);
  if (res.code === 200) {
    ElMessage.success('删除成功');
    loadUsers();
  } else {
    ElMessage.error(res.msg || '删除失败');
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
    ElMessage.success('角色分配成功');
    roleDialogVisible.value = false;
  } else {
    ElMessage.error(res.msg || '分配失败');
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
    ElMessage.warning('新密码至少 10 位，且需包含字母、数字和特殊字符');
    return;
  }
  const res = await resetPassword(resetForm);
  if (res.code === 200) {
    ElMessage.success('密码重置成功');
    resetDialogVisible.value = false;
  } else {
    ElMessage.error(res.msg || '重置失败');
  }
}

onMounted(() => {
  loadUsers();
  loadRoles();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

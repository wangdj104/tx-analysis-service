<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>角色管理</h1>
                <p class="subtitle">管理系统角色及菜单权限配置。</p>
              </div>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <div class="toolbar">
            <el-button v-if="isAdmin" type="primary" @click="showAddDialog">
              <el-icon><Plus /></el-icon>新增角色
            </el-button>
          </div>
          <el-table :data="roles" stripe class="app-data-table">
            <el-table-column v-if="roleColVisible('roleCode')" prop="roleCode" label="编码" min-width="108" />
            <el-table-column v-if="roleColVisible('roleName')" prop="roleName" label="名称" min-width="108" />
            <el-table-column v-if="roleColVisible('description')" prop="description" label="说明" min-width="160" show-overflow-tooltip />
            <el-table-column v-if="roleColVisible('status')" prop="status" label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">正常</el-tag>
                <el-tag v-else type="danger" size="small">停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" align="center" fixed="right">
              <template #header>
                <TableActionHeader v-model="roleVisibleCols" :columns="ROLE_COLUMN_DEFS" @reset="resetRoleColumns" />
              </template>
              <template #default="{ row }">
                <template v-if="isAdmin">
                  <el-button link type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
                  <el-button link type="primary" size="small" @click="showMenuDialog(row)">配置菜单</el-button>
                  <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                    <template #reference>
                      <el-button link type="danger" size="small" :disabled="row.roleCode === 'admin'">删除</el-button>
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
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px" :rules="rules" ref="formRef">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="form.roleCode" :disabled="isEdit || form.roleCode === 'admin'" placeholder="例如：editor" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="例如：编辑人员" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="角色说明" />
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

    <!-- configurationMenuPermission -->
    <el-dialog v-model="menuDialogVisible" title="配置菜单权限" width="520px" destroy-on-close>
      <el-alert v-if="currentRole?.roleCode === 'admin'" title="管理员角色的菜单权限不能在此页面编辑，如需调整请由管理员更新数据库。" type="warning" :closable="false" style="margin-bottom: 16px" />
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
        <el-button @click="menuDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAssignMenus" :disabled="currentRole?.roleCode === 'admin'">保存</el-button>
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
  { key: 'roleCode', label: '编码' },
  { key: 'roleName', label: '名称' },
  { key: 'description', label: '说明', default: false },
  { key: 'status', label: '状态' }
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
  roleCode: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
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
      ElMessage.success(res.data || '保存成功');
      dialogVisible.value = false;
      loadRoles();
    } else {
      ElMessage.error(res.msg || '保存失败');
    }
  } catch (e) {
    console.error(e);
  }
}

async function handleDelete(id) {
  const res = await deleteRole(id);
  if (res.code === 200) {
    ElMessage.success('删除成功');
    loadRoles();
  } else {
    ElMessage.error(res.msg || '删除失败');
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
    ElMessage.success('菜单权限更新成功');
    menuDialogVisible.value = false;
  } else {
    ElMessage.error(res.msg || '配置失败');
  }
}

onMounted(() => {
  loadRoles();
  loadMenus();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

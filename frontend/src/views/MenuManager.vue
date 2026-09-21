<template>
  <main class="menu-manager module-page">
    <section class="page-inner">
      <header class="page-header">
        <div><h1>菜单管理</h1><p class="subtitle">统一维护导航层级、路由地址、权限标识和显示顺序。</p></div>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新增菜单</el-button>
      </header>
      <section class="content-panel" v-loading="loading">
        <el-table :data="tree" row-key="id" default-expand-all border>
          <el-table-column prop="menuName" label="菜单名称" min-width="180" />
          <el-table-column prop="menuCode" label="菜单编码" min-width="190" />
          <el-table-column prop="menuPath" label="路由" min-width="220" show-overflow-tooltip />
          <el-table-column prop="permission" label="权限标识" min-width="210" />
          <el-table-column prop="sortOrder" label="顺序" width="76" align="center" />
          <el-table-column label="状态" width="86" align="center"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button><el-popconfirm title="删除父菜单前，请确认其下没有子菜单。是否继续？" @confirm="remove(row)"><template #reference><el-button link type="danger">删除</el-button></template></el-popconfirm></template></el-table-column>
        </el-table>
      </section>
    </section>
    <el-dialog v-model="visible" :title="form.id ? '编辑菜单' : '新增菜单'" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="上级菜单"><el-select v-model="form.parentId" style="width:100%"><el-option label="一级菜单" :value="0" /><el-option v-for="item in parentOptions" :key="item.id" :label="item.menuName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="菜单名称" prop="menuName"><el-input v-model="form.menuName" /></el-form-item>
        <el-form-item label="菜单编码" prop="menuCode"><el-input v-model="form.menuCode" placeholder="例如：medication-reminder" /></el-form-item>
        <el-form-item label="路由"><el-input v-model="form.menuPath" placeholder="页面路由或含参数路由，例如 /path?tab=x" /></el-form-item>
        <el-form-item label="图标"><el-input v-model="form.menuIcon" /></el-form-item>
        <el-form-item label="权限标识"><el-input v-model="form.permission" placeholder="例如：medication:reminder:view" /></el-form-item>
        <el-form-item label="顺序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
    </el-dialog>
  </main>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { deleteMenu, getMenuList, saveMenu } from '@/api/menu';
const loading=ref(false),saving=ref(false),visible=ref(false),formRef=ref(null),menus=ref([]);
const form=reactive({id:null,parentId:0,menuName:'',menuCode:'',menuPath:'',menuIcon:'Menu',permission:'',menuType:1,sortOrder:0,status:1});
const rules={menuName:[{required:true,message:'请输入菜单名称',trigger:'blur'}],menuCode:[{required:true,message:'请输入菜单编码',trigger:'blur'}]};
const parentOptions=computed(()=>menus.value.filter(item=>item.parentId===0&&item.id!==form.id));
const tree=computed(()=>{const map=new Map(menus.value.map(item=>[item.id,{...item,children:[]}])) ;const roots=[];for(const item of map.values()){if(item.parentId&&map.has(item.parentId))map.get(item.parentId).children.push(item);else roots.push(item)};const sort=list=>{list.sort((a,b)=>(a.sortOrder||0)-(b.sortOrder||0));list.forEach(x=>sort(x.children))};sort(roots);return roots});
async function load(){loading.value=true;try{const res=await getMenuList();menus.value=res.data||[]}finally{loading.value=false}}
function reset(){Object.assign(form,{id:null,parentId:0,menuName:'',menuCode:'',menuPath:'',menuIcon:'Menu',permission:'',menuType:1,sortOrder:0,status:1})}
function openCreate(){reset();visible.value=true}
function openEdit(row){Object.assign(form,{...row});visible.value=true}
async function submit(){await formRef.value.validate();saving.value=true;try{await saveMenu({...form});ElMessage.success('菜单保存成功');visible.value=false;await load()}finally{saving.value=false}}
async function remove(row){await deleteMenu(row.id);ElMessage.success('菜单已删除');await load()}
onMounted(load);
</script>
<style scoped src="@/styles/module-layout.css"></style>
<style scoped>.menu-manager{padding:28px}.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h1{margin:0 0 6px}.subtitle{margin:0;color:#64748b}.content-panel{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:18px}</style>

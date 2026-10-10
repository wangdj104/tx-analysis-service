<template>
  <main class="menu-manager module-page">
    <section class="page-inner">
      <header class="page-header">
        <div><h1>Menu Management</h1><p class="subtitle">Manage the navigation hierarchy, routes, permission identifiers, and display order. </p></div>
        <el-button type="primary" @click="openCreate"><el-icon><Plus /></el-icon>Add menu</el-button>
      </header>
      <section class="content-panel" v-loading="loading">
        <el-table :data="tree" row-key="id" default-expand-all border>
          <el-table-column prop="menuName" label="Menu name" min-width="180" />
          <el-table-column prop="menuCode" label="Menu code" min-width="190" />
          <el-table-column prop="menuPath" label="route" min-width="220" show-overflow-tooltip />
          <el-table-column prop="permission" label="Permission identifier" min-width="210" />
          <el-table-column prop="sortOrder" label="Order" width="76" align="center" />
          <el-table-column label="Status" width="86" align="center"><template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? 'Enabled' : 'Disabled' }}</el-tag></template></el-table-column>
          <el-table-column label="Actions" width="150" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">Edit</el-button><el-popconfirm title="Before deleting this parent menu, confirm that it has no child menus. Continue?" @confirm="remove(row)"><template #reference><el-button link type="danger">Delete</el-button></template></el-popconfirm></template></el-table-column>
        </el-table>
      </section>
    </section>
    <el-dialog v-model="visible" :title="form.id ? 'Edit menu' : 'Add menu'" width="620px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="Parent menu"><el-select v-model="form.parentId" style="width:100%"><el-option label="Top-level menu" :value="0" /><el-option v-for="item in parentOptions" :key="item.id" :label="item.menuName" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="Menu name" prop="menuName"><el-input v-model="form.menuName" /></el-form-item>
        <el-form-item label="Menu code" prop="menuCode"><el-input v-model="form.menuCode" placeholder="for example: medication-reminder" /></el-form-item>
        <el-form-item label="route"><el-input v-model="form.menuPath" placeholder="Page route, optionally with a query string, for example: /path?tab=x" /></el-form-item>
        <el-form-item label="Icon"><el-input v-model="form.menuIcon" /></el-form-item>
        <el-form-item label="Permission identifier"><el-input v-model="form.permission" placeholder="for example: medication:reminder:view" /></el-form-item>
        <el-form-item label="Order"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="Status"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submit">Save</el-button></template>
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
const rules={menuName:[{required:true,message:'Enter Menu name',trigger:'blur'}],menuCode:[{required:true,message:'Enter Menu code',trigger:'blur'}]};
const parentOptions=computed(()=>menus.value.filter(item=>item.parentId===0&&item.id!==form.id));
const tree=computed(()=>{const map=new Map(menus.value.map(item=>[item.id,{...item,children:[]}])) ;const roots=[];for(const item of map.values()){if(item.parentId&&map.has(item.parentId))map.get(item.parentId).children.push(item);else roots.push(item)};const sort=list=>{list.sort((a,b)=>(a.sortOrder||0)-(b.sortOrder||0));list.forEach(x=>sort(x.children))};sort(roots);return roots});
async function load(){loading.value=true;try{const res=await getMenuList();menus.value=res.data||[]}finally{loading.value=false}}
function reset(){Object.assign(form,{id:null,parentId:0,menuName:'',menuCode:'',menuPath:'',menuIcon:'Menu',permission:'',menuType:1,sortOrder:0,status:1})}
function openCreate(){reset();visible.value=true}
function openEdit(row){Object.assign(form,{...row});visible.value=true}
async function submit(){await formRef.value.validate();saving.value=true;try{await saveMenu({...form});ElMessage.success('Menu saved successfully');visible.value=false;await load()}finally{saving.value=false}}
async function remove(row){await deleteMenu(row.id);ElMessage.success('Menu deleted');await load()}
onMounted(load);
</script>
<style scoped src="@/styles/module-layout.css"></style>
<style scoped>.menu-manager{padding:28px}.page-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:18px}.page-header h1{margin:0 0 6px}.subtitle{margin:0;color:var(--ink-500)}.content-panel{background:var(--paper);border:1px solid var(--line);border-radius:16px;padding:18px}</style>

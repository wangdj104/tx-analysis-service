import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import {ref,reactive,computed} from 'vue'
import * as copy from '../src/utils/serverText.js'
const src=file=>fs.readFileSync(new URL('../src/views/'+file,import.meta.url),'utf8')

test('built-in role and menu names have Chinese display labels without changing editable values',()=>{
 assert.equal(typeof copy.localizeRoleName,'function')
 assert.equal(typeof copy.localizeMenuName,'function')
 const role={roleCode:'doctor',roleName:'Doctor',description:'Clinical review and assigned-patient management'}
 const menu={menuCode:'system-user',menuName:'User Management'}
 assert.equal(copy.localizeRoleName(role),'医生');assert.equal(copy.localizeMenuName(menu),'用户管理')
 assert.equal(copy.localizeRoleDescription(role),'临床审核与分配患者管理')
 assert.equal(role.roleName,'Doctor');assert.equal(menu.menuName,'User Management')
 assert.equal(copy.localizeRoleName({...role,roleName:'Patient'}),'Patient')
 assert.equal(copy.localizeRoleDescription({...role,description:'Normal'}),'Normal')
 assert.equal(copy.localizeMenuName({...menu,menuName:'Normal'}),'Normal')
})
test('Chinese admin table, parent choices and role assignment apply labels only at rendering',()=>{
 assert.ok(src('MenuManager.vue').includes('localizeMenuName(row)'))
 assert.ok(src('MenuManager.vue').includes('localizeMenuName(item)'))
 assert.ok(src('RoleManager.vue').includes('localizeRoleName(row)'))
 assert.ok(src('RoleManager.vue').includes('localizeRoleDescription(row)'))
 assert.ok(src('RoleManager.vue').includes('localizeMenuName(data)'))
 assert.ok(src('UserManager.vue').includes('localizeRoleName(role)'))
})
test('opening a built-in role editor retains the original stored label and description',async()=>{
 const raw={id:1,roleCode:'specialty_dialysis',roleName:'Dialysis Patient',description:'Patient-specific dialysis menu scope'}
 const script=src('RoleManager.vue').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm,'')
 const bindings={ref,reactive,computed,onMounted(){},useTableColumns:()=>({}),getRoleList:async()=>({code:200,data:[raw]}),...copy}
 const view=new Function(...Object.keys(bindings),script+';return {loadRoles,roles,showEditDialog,form}')(...Object.values(bindings))
 await view.loadRoles();assert.deepEqual(view.roles.value[0],raw)
 view.showEditDialog(view.roles.value[0]);assert.equal(view.form.roleName,raw.roleName);assert.equal(view.form.description,raw.description)
})

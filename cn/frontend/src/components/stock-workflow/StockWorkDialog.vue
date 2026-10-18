<template>
 <el-dialog :model-value="flow.state.dialog" title="库存证据" width="min(520px,94vw)" :close-on-click-modal="false" @close="flow.close()">
  <form v-if="draft" @submit.prevent="submit"><p>患者 {{ flow.state.detail?.patientId }} · 库存 {{ flow.state.detail?.stockId }}</p>
   <label v-if="'assignedUserId' in draft">当前具有用药写权限的负责人 ID<input v-model="draft.assignedUserId" inputmode="numeric" required/></label>
   <label v-if="'quantity' in draft">实际数量<input v-model="draft.quantity" inputmode="decimal" required pattern="(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,3})?"/></label>
   <label v-if="'unit' in draft">原账本单位（须完全一致）<input :value="draft.unit" readonly/></label>
   <label v-if="'dailyUseQuantity' in draft">明确知道的每日用量，不知道请留空<input v-model="draft.dailyUseQuantity" inputmode="decimal"/></label>
   <label v-if="'actualAt' in draft">实际收到时间（UTC，例如 2026-10-14T12:00:00Z）<input v-model="draft.actualAt" required/></label>
   <label v-if="'countedAt' in draft">实物盘点时间（UTC）<input v-model="draft.countedAt" required/></label>
   <template v-if="'warningQuantity' in draft"><label>数量提醒阈值<input v-model="draft.warningQuantity" inputmode="decimal" required/></label><label>剩余天数提醒阈值<input v-model="draft.warningDays" type="number" min="0" max="36500" required/></label></template>
   <fieldset v-if="'countChoice' in draft"><legend>这条真实事实是否已包含在当前实物盘点中？</legend><label><input v-model="draft.countChoice" type="radio" value="ACTUAL_AMOUNT" required/>实际数量尚未计入，需要记入原生账本</label><label><input v-model="draft.countChoice" type="radio" value="INCLUDED_IN_COUNT" required :disabled="!draft.countId"/>已包含在所示当前盘点中，不再重复扣账</label><p>不会为你预选。数量或历史单位未知时仍保持未核清。</p></fieldset>
   <label>证据 / 原因<textarea v-model="draft.reason" maxlength="1000" required/></label>
   <p v-if="['invalid','conflict'].includes(flow.state.phase)" role="alert">{{ flow.state.error }}. 请刷新当前库存并核对证据，然后发起新命令。</p>
   <button type="submit" :disabled="['submitting','unknown','invalid','conflict'].includes(flow.state.phase)">保存证据</button><button type="button" @click="flow.close()">关闭</button>
  </form>
 </el-dialog>
</template>
<script setup>
import {computed} from 'vue';const props=defineProps({flow:{type:Object,required:true}});const draft=computed(()=>props.flow.state.draft);
// countChoice: '' is intentional. No count-inclusion default is safe.
function submit(){const d=draft.value;if(!d)return;if('assignedUserId' in d)d.assignedUserId=Number(d.assignedUserId);if('warningDays' in d)d.warningDays=Number(d.warningDays);if('dailyUseQuantity' in d&&!d.dailyUseQuantity)delete d.dailyUseQuantity;if('countChoice' in d&&d.countChoice==='ACTUAL_AMOUNT'&&!d.countId){delete d.countId;delete d.countVersion;}props.flow.submit();}
</script>
<style scoped>label{display:block;min-height:44px;margin:.75rem 0}input:not([type=radio]),textarea{box-sizing:border-box;width:100%;min-height:44px;color:var(--el-text-color-primary);background:var(--el-bg-color);border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);padding:.5rem}button{min-height:44px;min-width:44px;padding:.6rem;margin:.25rem}input[type=radio]{min-height:24px;min-width:24px;vertical-align:middle}fieldset{border:1px solid var(--el-border-color)}textarea{min-height:88px}</style>

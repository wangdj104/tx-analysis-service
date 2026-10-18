<template>
 <section aria-label="库存详情"><h3>{{ d.drugName }}</h3><p>当前余额： {{ d.quantity == null ? '—' : d.quantity }} {{ d.unit }} · 周期 {{ d.cycle || '—' }}</p>
 <StockNotificationStatus :policy="d.reminderPolicy" :stock-id="d.stockId" @review="$emit('notification-review',$event)" />
 <p>{{ stateText[d.state] || d.state }}</p><p v-if="!d.usageKnown" role="status">每日用量未知，无法据此确认库存充足或安全。</p>
 <p v-if="d.responsibleCurrent===false" role="status">当前负责人已无用药写入权限，请通过“修改人工规则”选择当前有权写入的负责人，此操作不会授予新权限。</p>
 <p>未核清事实： {{ d.unresolvedCount }}. 实物盘点不会自动解决未知事实。</p>
 <p v-if="d.currentCount">当前周期盘点： {{ d.currentCount.quantity }} {{ d.currentCount.unit }} · {{ d.currentCount.counted_at }}</p>
 <div><button v-for="command in d.allowedCommands.filter(x=>x!=='RESOLVE_INTAKE')" :key="command" type="button" :disabled="busy" @click="$emit('command',command)">{{ labels[command] }}</button><button type="button" @click="flow.close()">关闭详情</button></div>
 <ul><li v-for="row in d.unresolved" :key="row.intake_id">服药事实 {{ row.intake_id }} · {{ row.unit || '单位未知' }} <button v-if="row.action_id && d.allowedCommands.includes('RESOLVE_INTAKE')" type="button" :disabled="busy" @click="$emit('command','RESOLVE_INTAKE',row)">核对是否已计入盘点</button><span v-else>请在用药跟进中追加真实的事实更正</span></li></ul>
 <p v-if="d.unresolvedWindowLimited">此处仅显示前 1000 条未核清事实。解决后刷新可推进队列，结案仍会检查全部事实。</p>
 <button type="button" @click="flow.open(d.stockId)">刷新待核对队列</button><button type="button" @click="flow.loadEvents()">事件历史</button><button type="button" @click="flow.loadDeliveries()">我的通知状态</button>
 <ul><li v-for="event in flow.state.events" :key="event.id">{{ event.type }} · {{ event.occurred_at }} · {{ event.settlement_status }}</li></ul><button v-if="flow.state.eventCursor" type="button" @click="flow.loadEvents(true)">更多事件</button>
 <ul><li v-for="row in flow.state.deliveries" :key="row.id">{{ row.status }} · {{ row.lastResult }}<label v-if="row.canRetry&&row.requiresDuplicateRiskAcknowledgement"><input v-model="risk[row.id]" type="checkbox"/>重试可能重复发送</label><button v-if="row.canRetry" type="button" :disabled="row.requiresDuplicateRiskAcknowledgement&&!risk[row.id]" @click="flow.retryDelivery(row,!!risk[row.id])">重试通知</button></li></ul><button v-if="flow.state.deliveryCursor" type="button" @click="flow.loadDeliveries(true)">更多投递尝试</button><p>已投递仅代表服务商接受消息。</p>
 </section>
</template>
<script setup>
import StockNotificationStatus from '@/components/stock-notification/StockNotificationStatus.vue';
import {computed,reactive} from 'vue';const props=defineProps({flow:{type:Object,required:true}});defineEmits(['command','notification-review']);const d=computed(()=>props.flow.state.detail),busy=computed(()=>['submitting','unknown'].includes(props.flow.state.phase)),risk=reactive({});
const labels={ADOPT_STOCK:'开始库存核对',RECORD_REPLENISHMENT:'记录实际补充',RECORD_COUNT:'记录实物盘点',RECONCILE:'明确核清并结案',CHANGE_RULE:'修改人工规则',CANCEL_MISTAKE:'取消误建核对'};const stateText={UNTRACKED:'选择“开始库存核对”来跟踪这本原生库存账',OPEN:'待核对：检查事实并记录当前实物盘点',RESOLVED:'已依据本周期记录的证据核清',NOT_APPLICABLE:'人工规则已变更，当前事项不再适用',CANCELLED_MISTAKE:'已作为误建取消'};
</script>
<style scoped>button{min-height:44px;min-width:44px;padding:.6rem;margin:.25rem;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);background:var(--el-fill-color-light);color:inherit}li{margin:.5rem 0;overflow-wrap:anywhere}label{display:block;min-height:44px;padding:.5rem}input{min-height:24px;min-width:24px}</style>

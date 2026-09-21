import request from '@/utils/request'
export const listHealthAutomations = () => request({url:'/health-analysis/automations'})
export const saveHealthAutomation = data => request({url:'/health-analysis/automations',method:'post',data})
export const deleteHealthAutomation = id => request({url:`/health-analysis/automations/${id}`,method:'delete'})
export const runHealthAutomation = id => request({url:`/health-analysis/automations/${id}/run`,method:'post'})

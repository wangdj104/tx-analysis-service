/** Only offset-bearing workflow timestamps are localized. Native naive wall times stay verbatim. */
export function formatWorkflowTime(value) {
 if(!value)return '—'
 if(typeof value!=='string'||!/(Z|[+-]\d{2}:\d{2})$/.test(value))return value
 const date=new Date(value)
 if(!Number.isFinite(date.getTime()))return value
 const pad=value=>String(value).padStart(2,'0'),offset=-date.getTimezoneOffset(),sign=offset<0?'-':'+'
 return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())} UTC${sign}${pad(Math.floor(Math.abs(offset)/60))}:${pad(Math.abs(offset)%60)}`
}

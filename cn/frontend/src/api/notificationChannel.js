import request from '@/utils/request';
export const listNotificationChannels = () => request({ url: '/notification-channel/list', method: 'get' });
export const saveNotificationChannel = data => request({ url: '/notification-channel/save', method: 'post', data });
export const testNotificationChannel = id => request({ url: `/notification-channel/test/${id}`, method: 'post' });
export const deleteNotificationChannel = id => request({ url: `/notification-channel/${id}`, method: 'delete' });

import request from '@/utils/request';

export const getDoctorSummary = () => request({ url: '/doctor-workspace/summary', method: 'get' });
export const getDoctorPatients = () => request({ url: '/doctor-workspace/patients', method: 'get' });
export const getDoctorReviews = status => request({ url: '/doctor-workspace/reviews', method: 'get', params: { status } });
export const getDoctorNotes = patientId => request({ url: '/doctor-workspace/notes', method: 'get', params: { patientId } });
export const getDoctorPlans = patientId => request({ url: '/doctor-workspace/plans', method: 'get', params: { patientId } });
export const saveDoctorNote = data => request({ url: '/doctor-workspace/notes', method: 'post', data });
export const saveDoctorPlan = data => request({ url: '/doctor-workspace/plans', method: 'post', data });
export const completeDoctorReview = data => request({ url: '/doctor-workspace/reviews', method: 'post', data });

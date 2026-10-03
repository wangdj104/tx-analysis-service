/** Production edition mount; business APIs and the distinct static demo keep their origin paths. */
export function appPath(path, edition=process.env.CARE_PLAN_E2E_LANGUAGE || 'en') {
  if(typeof path!=='string' || !path.startsWith('/') || path.startsWith('//') || !['en','cn'].includes(edition))throw new Error('Invalid edition application path')
  if(edition==='en' || path.startsWith('/api/') || path.startsWith('/__demo/') || path==='/cn' || path.startsWith('/cn/'))return path
  return '/cn'+path
}

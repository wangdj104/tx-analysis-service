import request from '@/utils/request'

// gethas active indicators
export function listIndicators() {
  return request({
    url: '/health-indicator/list',
    method: 'get'
  })
}

// by categorygetactive indicators
export function listByCategory(category) {
  return request({
    url: '/health-indicator/list-by-category',
    method: 'get',
    params: { category }
  })
}

// based oninputNameparsefor standarditemCode
export function resolveName(name) {
  return request({
    url: '/health-indicator/resolve-name',
    method: 'get',
    params: { name }
  })
}

// based onitemCodegetindicatorDetails
export function getIndicatorDetail(itemCode) {
  return request({
    url: `/health-indicator/detail/${itemCode}`,
    method: 'get'
  })
}

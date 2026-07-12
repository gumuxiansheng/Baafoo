import axios from 'axios'

const http = axios.create({
  baseURL: '/__baafoo__/api',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('baafoo_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const locale = localStorage.getItem('baafoo_locale') || 'zh-CN'
  config.headers['Accept-Language'] = locale
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      const hasToken = !!localStorage.getItem('baafoo_token')
      localStorage.removeItem('baafoo_token')
      localStorage.removeItem('baafoo_role')
      localStorage.removeItem('baafoo_username')
      // Only redirect to login if the user was previously authenticated.
      // Guest users (no token) should stay on the current page.
      if (hasToken && window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
    }
    if (!error.response) {
      console.error('Network Error: backend server may not be running')
      const msg = 'Unable to connect to server. Please verify that the backend service is running.'
      return { success: false, code: 0, message: msg, data: null }
    }
    const msg = error.response?.data?.message || error.message
    console.error('API Error:', msg)
    return { success: false, code: error.response?.status || 500, message: msg, data: null }
  }
)

export default {
  // --- Auth ---
  login: (username, password) => http.post('/auth/login', { username, password }),
  getAuthMe: () => http.get('/auth/me'),

  // --- Users ---
  getUsers: () => http.get('/users'),
  createUser: (data) => http.post('/users', data),
  importUsersCsv: (csvText) => http.post('/users/import', csvText, {
    headers: { 'Content-Type': 'text/csv' }
  }),
  updateUserRole: (username, role) => http.put(`/users/${username}/role`, { role }),
  deleteUser: (username) => http.delete(`/users/${username}`),
  generateApiKey: (username) => http.post(`/users/${username}/api-key`),
  revokeApiKey: (username) => http.delete(`/users/${username}/api-key`),

  // --- Rules ---
  getRules: () => http.get('/rules'),
  getRulesPaged: (protocol, keyword, environment, host, page = 1, size = 20, sortBy, sortOrder) => {
    const params = new URLSearchParams({ page, size })
    if (protocol) params.set('protocol', protocol)
    if (keyword) params.set('keyword', keyword)
    if (environment) params.set('environment', environment)
    if (host) params.set('host', host)
    if (sortBy) params.set('sortBy', sortBy)
    if (sortOrder) params.set('sortOrder', sortOrder)
    return http.get(`/rules?${params.toString()}`)
  },
  getRule: (id) => http.get(`/rules/${id}`),
  createRule: (data) => http.post('/rules', data),
  updateRule: (id, data) => http.put(`/rules/${id}`, data),
  deleteRule: (id) => http.delete(`/rules/${id}`),
  undoRule: (id) => http.post(`/rules/${id}/undo`),
  getInheritedEnvironments: (id) => http.get(`/rules/${id}/inherited-environments`),

  // OpenAPI Import
  importOpenApi: (jsonContent, { environment, save, prefix } = {}) => {
    const params = new URLSearchParams()
    if (environment) params.set('environment', environment)
    if (save) params.set('save', 'true')
    if (prefix) params.set('prefix', prefix)
    const query = params.toString()
    return http.post(`/rules/import-openapi${query ? '?' + query : ''}`, jsonContent, {
      headers: { 'Content-Type': 'application/json' }
    })
  },

  // Stateful Mock — counter reset
  resetRuleState: (id) => http.post(`/rules/${id}/reset-state`),
  resetAllRuleState: () => http.post('/rules/reset-all-state'),

  // --- Rule Sets ---
  getRuleSets: () => http.get('/rulesets'),
  createRuleSet: (data) => http.post('/rulesets', data),

  // --- Environments ---
  getEnvironments: () => http.get('/environments'),
  getEnvironment: (id) => http.get(`/environments/${id}`),
  createEnvironment: (data) => http.post('/environments', data),
  updateEnvironment: (id, data) => http.put(`/environments/${id}`, data),
  deleteEnvironment: (id) => http.delete(`/environments/${id}`),
  associateRulesToEnv: (id, ruleIds) => http.post(`/environments/${id}/rules`, { ruleIds }),
  dissociateRulesFromEnv: (id, ruleIds) => http.delete(`/environments/${id}/rules`, { data: { ruleIds } }),

  // --- Scenes ---
  getScenes: () => http.get('/scenes'),
  createScene: (data) => http.post('/scenes', data),
  updateScene: (id, data) => http.put(`/scenes/${id}`, data),
  deleteScene: (id) => http.delete(`/scenes/${id}`),

  // --- Recordings ---
  getRecordings: (ruleId, limit = 100) => http.get(`/recordings?ruleId=${ruleId || ''}&limit=${limit}`),
  getRecordingsPaged: (params, page = 1, size = 20) => {
    const p = new URLSearchParams({ page, size })
    if (params) {
      if (params.ruleId) p.set('ruleId', params.ruleId)
      if (params.agentId) p.set('agentId', params.agentId)
      if (params.agentIp) p.set('agentIp', params.agentIp)
      if (params.protocol) p.set('protocol', params.protocol)
      if (params.method) p.set('method', params.method)
      if (params.path) p.set('path', params.path)
      if (params.statusCode) p.set('statusCode', params.statusCode)
      if (params.keyword) p.set('keyword', params.keyword)
    }
    return http.get(`/recordings?${p.toString()}`)
  },
  deleteRecording: (id) => http.delete(`/recordings/${id}`),

  // --- Agents ---
  getAgents: () => http.get('/agents'),

  // --- Status ---
  getStatus: () => http.get('/status'),

  // --- Chaos Engineering ---
  chaosActivate: (profileName) => http.post('/chaos/profiles/activate', { profileName }),
  chaosDeactivate: (profileName) => http.post('/chaos/profiles/deactivate', { profileName }),
  chaosStatus: () => http.get('/chaos/profiles/status'),
  chaosEmergencyStop: () => http.post('/chaos/emergency-stop')
}

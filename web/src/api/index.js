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
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('baafoo_token')
      localStorage.removeItem('baafoo_role')
      localStorage.removeItem('baafoo_username')
      if (window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
    }
    if (!error.response) {
      console.error('Network Error: backend server may not be running')
      return { success: false, code: 0, message: '无法连接到服务器，请确认后端服务已启动', data: null }
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
  getRulesPaged: (protocol, keyword, page = 1, size = 20) => {
    const params = new URLSearchParams({ page, size })
    if (protocol) params.set('protocol', protocol)
    if (keyword) params.set('keyword', keyword)
    return http.get(`/rules?${params.toString()}`)
  },
  getRule: (id) => http.get(`/rules/${id}`),
  createRule: (data) => http.post('/rules', data),
  updateRule: (id, data) => http.put(`/rules/${id}`, data),
  deleteRule: (id) => http.delete(`/rules/${id}`),
  undoRule: (id) => http.post(`/rules/${id}/undo`),
  getInheritedEnvironments: (id) => http.get(`/rules/${id}/inherited-environments`),

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
  getRecordingsPaged: (ruleId, page = 1, size = 20) => http.get(`/recordings?ruleId=${ruleId || ''}&page=${page}&size=${size}`),
  deleteRecording: (id) => http.delete(`/recordings/${id}`),

  // --- Agents ---
  getAgents: () => http.get('/agents'),

  // --- Status ---
  getStatus: () => http.get('/status')
}

import axios from 'axios'

const http = axios.create({
  baseURL: '/__baafoo__/api',
  timeout: 10000
})

// Response interceptor
http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const msg = error.response?.data?.message || error.message
    console.error('API Error:', msg)
    return { success: false, code: error.response?.status || 500, message: msg, data: null }
  }
)

export default {
  // --- Rules ---
  getRules: () => http.get('/rules'),
  getRule: (id) => http.get(`/rules/${id}`),
  createRule: (data) => http.post('/rules', data),
  updateRule: (id, data) => http.put(`/rules/${id}`, data),
  deleteRule: (id) => http.delete(`/rules/${id}`),
  undoRule: (id) => http.post(`/rules/${id}/undo`),

  // --- Rule Sets ---
  getRuleSets: () => http.get('/rulesets'),
  createRuleSet: (data) => http.post('/rulesets', data),

  // --- Environments ---
  getEnvironments: () => http.get('/environments'),
  getEnvironment: (id) => http.get(`/environments/${id}`),
  createEnvironment: (data) => http.post('/environments', data),
  updateEnvironment: (id, data) => http.put(`/environments/${id}`, data),
  deleteEnvironment: (id) => http.delete(`/environments/${id}`),

  // --- Scenes ---
  getScenes: () => http.get('/scenes'),
  createScene: (data) => http.post('/scenes', data),
  updateScene: (id, data) => http.put(`/scenes/${id}`, data),
  deleteScene: (id) => http.delete(`/scenes/${id}`),

  // --- Recordings ---
  getRecordings: (ruleId, limit = 100) => http.get(`/recordings?ruleId=${ruleId || ''}&limit=${limit}`),
  deleteRecording: (id) => http.delete(`/recordings/${id}`),

  // --- Agents ---
  getAgents: () => http.get('/agents'),

  // --- Status ---
  getStatus: () => http.get('/status')
}

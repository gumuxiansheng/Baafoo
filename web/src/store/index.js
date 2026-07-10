import { defineStore } from 'pinia'
import api from '@/api'

export const useStatusStore = defineStore('status', {
  state: () => ({
    status: null,
    loading: false
  }),
  actions: {
    async fetchStatus() {
      try {
        const res = await api.getStatus()
        if (res.success) {
          this.status = res.data
        }
      } catch (e) {
        // ignore
      }
    }
  }
})

export const useRulesStore = defineStore('rules', {
  state: () => ({
    rules: [],
    currentRule: null,
    loading: false,
    // Pagination state
    pagination: { page: 1, size: 20, total: 0 },
    filter: { protocol: '', keyword: '', environment: '', host: '' }
  }),
  actions: {
    async fetchRules() {
      this.loading = true
      try {
        const res = await api.getRules()
        if (res.success) this.rules = res.data
      } finally {
        this.loading = false
      }
    },
    async fetchRulesPaged() {
      this.loading = true
      try {
        const res = await api.getRulesPaged(this.filter.protocol, this.filter.keyword, this.filter.environment, this.filter.host, this.pagination.page, this.pagination.size)
        if (res.success && res.data) {
          this.rules = res.data.items || []
          this.pagination.total = res.data.total || 0
        }
      } finally {
        this.loading = false
      }
    },
    setPage(page) {
      this.pagination.page = page
    },
    setPageSize(size) {
      this.pagination.size = size
      this.pagination.page = 1
    },
    setFilter({ protocol, keyword, environment, host }) {
      this.filter.protocol = protocol ?? ''
      this.filter.keyword = keyword ?? ''
      this.filter.environment = environment ?? ''
      this.filter.host = host ?? ''
      this.pagination.page = 1
    },
    async fetchRule(id) {
      const res = await api.getRule(id)
      if (res.success) this.currentRule = res.data
    },
    async createRule(rule) {
      const res = await api.createRule(rule)
      if (res.success) await this.fetchRulesPaged()
      return res
    },
    async updateRule(id, rule) {
      const res = await api.updateRule(id, rule)
      if (res.success) await this.fetchRulesPaged()
      return res
    },
    async deleteRule(id) {
      const res = await api.deleteRule(id)
      if (res.success) await this.fetchRulesPaged()
      return res
    },
    async undoRule(id) {
      const res = await api.undoRule(id)
      if (res.success) await this.fetchRulesPaged()
      return res
    }
  }
})

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('baafoo_token') || null,
    // M23: don't trust localStorage for role — fetch from server on init
    role: null,
    username: localStorage.getItem('baafoo_username') || null,
    permissions: [],
    initialized: false
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    isAdmin: (state) => state.role === 'admin',
    isDeveloper: (state) => state.role === 'developer' || state.role === 'admin',
    isTester: (state) => state.role === 'tester' || state.role === 'developer' || state.role === 'admin',
    canWriteRule: (state) => ['admin', 'developer'].includes(state.role),
    canWriteScene: (state) => ['admin', 'developer', 'tester'].includes(state.role),
    canWriteEnvironment: (state) => state.role === 'admin',
    canWriteRecording: (state) => ['admin', 'developer', 'tester'].includes(state.role),
    canManageUsers: (state) => state.role === 'admin'
  },
  actions: {
    async login(username, password) {
      const res = await api.login(username, password)
      if (res.success && res.data) {
        this.token = res.data.token
        this.role = res.data.role
        this.username = username
        localStorage.setItem('baafoo_token', res.data.token)
        // M23: don't persist role in localStorage — it's fetched from server via fetchMe()
        localStorage.setItem('baafoo_username', username)
        await this.fetchMe()
        return true
      }
      return false
    },
    logout() {
      this.token = null
      this.role = null
      this.username = null
      this.permissions = []
      localStorage.removeItem('baafoo_token')
      localStorage.removeItem('baafoo_role')
      localStorage.removeItem('baafoo_username')
    },
    async fetchMe() {
      try {
        const res = await api.getAuthMe()
        if (res.success && res.data) {
          this.role = res.data.role
          this.username = res.data.username
          this.permissions = res.data.permissions || []
          // M23: role is kept in memory only, not persisted to localStorage
          if (res.data.username) {
            localStorage.setItem('baafoo_username', res.data.username)
          }
          this.initialized = true
        }
      } catch (e) {
        this.initialized = true
      }
    }
  }
})

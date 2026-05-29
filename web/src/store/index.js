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
    loading: false
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
    async fetchRule(id) {
      const res = await api.getRule(id)
      if (res.success) this.currentRule = res.data
    },
    async createRule(rule) {
      const res = await api.createRule(rule)
      if (res.success) await this.fetchRules()
      return res
    },
    async updateRule(id, rule) {
      const res = await api.updateRule(id, rule)
      if (res.success) await this.fetchRules()
      return res
    },
    async deleteRule(id) {
      const res = await api.deleteRule(id)
      if (res.success) await this.fetchRules()
      return res
    },
    async undoRule(id) {
      const res = await api.undoRule(id)
      if (res.success) await this.fetchRules()
      return res
    }
  }
})

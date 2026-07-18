<template>
  <el-dropdown @command="switchLocale" trigger="click">
    <span class="locale-switcher">
      <el-icon><Switch /></el-icon>
      <span class="locale-label">{{ currentLabel }}</span>
      <el-icon class="locale-arrow"><ArrowDown /></el-icon>
    </span>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="zh-CN" :class="{ 'is-active': currentLocale === 'zh-CN' }">
          🇨🇳 中文
        </el-dropdown-item>
        <el-dropdown-item command="en" :class="{ 'is-active': currentLocale === 'en' }">
          🇺🇸 English
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { locale } = useI18n()

const currentLocale = computed(() => locale.value)
const currentLabel = computed(() => locale.value === 'zh-CN' ? '中文' : 'EN')

function switchLocale(lang) {
  if (lang === locale.value) return
  locale.value = lang
  localStorage.setItem('baafoo_locale', lang)

  // M-5: Removed the dead $ELEMENT/$elLocales block — this app uses Element Plus with the
  // ElConfigProvider component (set in App.vue) for locale switching, not the legacy
  // global $ELEMENT property. The window.location.reload() below re-mounts the app and
  // is enough for all components to pick up the new locale cleanly.
  window.location.reload()
}
</script>

<style scoped>
.locale-switcher {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--bf-text-secondary);
  cursor: pointer;
  transition: background 0.15s;
}
.locale-switcher:hover {
  background: var(--bf-fill-color);
}
.locale-label {
  min-width: 24px;
  text-align: center;
}
.locale-arrow {
  font-size: 10px;
  opacity: 0.5;
}
.is-active {
  font-weight: 700;
  color: var(--bf-accent);
}
</style>

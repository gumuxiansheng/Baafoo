import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import en from 'element-plus/dist/locale/en.mjs'

import App from './App.vue'
import router from './router'
import zhMessages from './locales/zh-CN.json'
import enMessages from './locales/en.json'

// Read persisted locale, default to zh-CN
const savedLocale = localStorage.getItem('baafoo_locale') || 'zh-CN'

const i18n = createI18n({
  legacy: false,
  locale: savedLocale,
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhMessages,
    'en': enMessages
  }
})

// Attach locale change handler to keep localStorage and Element Plus in sync
i18n.global.availableLocales.forEach((loc) => {
  // nothing needed here; localese is read from localStorage on init
})

const app = createApp(App)

// Register all Element Plus icons
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// Provide Element Plus locale globally via globalProperties
const elLocales = { 'zh-CN': zhCn, 'en': en }
app.config.globalProperties.$elLocales = elLocales
app.config.globalProperties.$i18n = i18n

app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(ElementPlus, { locale: elLocales[savedLocale] || zhCn })
app.mount('#app')

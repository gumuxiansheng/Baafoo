import { createRouter, createWebHashHistory } from 'vue-router'
import LoginPage from '@/views/LoginPage.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: LoginPage,
    meta: { title: 'login.title', public: true }
  },
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('@/views/DashboardPage.vue'),
    meta: { title: 'nav.dashboard' }
  },
  {
    path: '/rules',
    name: 'Rules',
    component: () => import('@/views/RulesPage.vue'),
    meta: { title: 'nav.rules' }
  },
  {
    path: '/rules/:id',
    name: 'RuleEditor',
    component: () => import('@/views/RuleEditorPage.vue'),
    meta: { title: 'nav.ruleEditor' }
  },
  {
    path: '/scenes',
    name: 'Scenes',
    component: () => import('@/views/ScenesPage.vue'),
    meta: { title: 'nav.scenes' }
  },
  {
    path: '/logs',
    name: 'Logs',
    component: () => import('@/views/LogsPage.vue'),
    meta: { title: 'nav.logs' }
  },
  {
    path: '/recordings',
    name: 'Recordings',
    component: () => import('@/views/RecordingsPage.vue'),
    meta: { title: 'nav.recordings' }
  },
  {
    path: '/environments',
    name: 'Environments',
    component: () => import('@/views/EnvironmentsPage.vue'),
    meta: { title: 'nav.environments' }
  },
  {
    path: '/environments/:id',
    name: 'EnvironmentDetail',
    component: () => import('@/views/EnvironmentDetailPage.vue'),
    meta: { title: 'nav.environmentDetail' }
  },
  {
    path: '/users',
    name: 'Users',
    component: () => import('@/views/UsersPage.vue'),
    meta: { title: 'nav.users', requireAdmin: true }
  },
  {
    path: '/status',
    name: 'Status',
    component: () => import('@/views/StatusPage.vue'),
    meta: { title: 'nav.status' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// Auth guard
import { useAuthStore } from '@/store'

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  // Allow public pages
  if (to.meta.public) {
    return next()
  }

  // Check if logged in
  if (!authStore.token) {
    return next('/login')
  }

  // Check admin requirement
  if (to.meta.requireAdmin && authStore.role !== 'admin') {
    return next('/')
  }

  next()
})

export default router


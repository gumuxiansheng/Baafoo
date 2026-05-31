import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginPage.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/DashboardPage.vue'),
    meta: { title: '仪表盘' }
  },
  {
    path: '/rules',
    name: 'Rules',
    component: () => import('@/views/RulesPage.vue'),
    meta: { title: '规则管理' }
  },
  {
    path: '/rules/:id',
    name: 'RuleEditor',
    component: () => import('@/views/RuleEditorPage.vue'),
    meta: { title: '规则编辑' }
  },
  {
    path: '/scenes',
    name: 'Scenes',
    component: () => import('@/views/ScenesPage.vue'),
    meta: { title: '场景集' }
  },
  {
    path: '/logs',
    name: 'Logs',
    component: () => import('@/views/LogsPage.vue'),
    meta: { title: '请求日志' }
  },
  {
    path: '/recordings',
    name: 'Recordings',
    component: () => import('@/views/RecordingsPage.vue'),
    meta: { title: '录制管理' }
  },
  {
    path: '/environments',
    name: 'Environments',
    component: () => import('@/views/EnvironmentsPage.vue'),
    meta: { title: '环境管理' }
  },
  {
    path: '/environments/:id',
    name: 'EnvironmentDetail',
    component: () => import('@/views/EnvironmentDetailPage.vue'),
    meta: { title: '环境详情' }
  },
  {
    path: '/users',
    name: 'Users',
    component: () => import('@/views/UsersPage.vue'),
    meta: { title: '用户管理', requireAdmin: true }
  },
  {
    path: '/status',
    name: 'Status',
    component: () => import('@/views/StatusPage.vue'),
    meta: { title: '系统状态' }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  document.title = (to.meta.title ? to.meta.title + ' - ' : '') + 'Baafoo'

  if (to.meta.requireAdmin) {
    const role = localStorage.getItem('baafoo_role')
    if (role !== 'admin') {
      next('/')
      return
    }
  }

  next()
})

export default router

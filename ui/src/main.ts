import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import './user-pages.css'
import './simple-theme.css'
import './admin-pages.css'
import './overview-dashboard.css'
import './ranking-pages.css'
import './glass-theme.css'
import App from './App.vue'

createApp(App).use(ElementPlus).mount('#app')

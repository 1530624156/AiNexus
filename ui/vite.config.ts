import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：模板里的 el-* 组件自动导入 JS + 样式，
    // 不再把整个组件库打进主包
    Components({
      resolvers: [
        ElementPlusResolver(),
      ],
      directives: true,
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 拆分大依赖为独立 chunk：浏览器对同一域名并行开多条连接下载，
        // 在高延迟/单连接受限的链路上比单一大文件快数倍；
        // 各 chunk 独立带哈希缓存，升级时未变的 chunk 无需重新下载
        manualChunks: {
          vue: ['vue'],
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
          echarts: ['echarts'],
        },
      },
    },
  },
})

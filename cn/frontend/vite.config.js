import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

const DEFAULT_PROXY_TARGET = 'http://localhost:8082';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = (env.DEV_PROXY_TARGET && String(env.DEV_PROXY_TARGET).trim()) || DEFAULT_PROXY_TARGET;

  return {
    base: '/cn/',
    plugins: [vue()],
    resolve: {
      alias: {
        '@': '/src',
      },
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined;
            if (id.includes('echarts') || id.includes('zrender')) return 'charts';
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus';
            if (id.includes('/vue/') || id.includes('vue-router') || id.includes('@vueuse')) return 'vue-vendor';
            return 'vendor';
          },
        },
      },
    },
    server: {
      port: 5174,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
          timeout: 1_200_000,
          proxyTimeout: 1_200_000,
        },
      },
    },
  };
});

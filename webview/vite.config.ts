import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react-swc';
import { viteSingleFile } from 'vite-plugin-singlefile';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const preserveDebugConsole = env.VITE_ENABLE_VCONSOLE === 'true';

  return {
    plugins: [
      react(),
      viteSingleFile(),
    ],
    build: {
      minify: 'esbuild',
      esbuild: {
        drop: preserveDebugConsole ? ['debugger'] : ['console', 'debugger'],
      },
      assetsInlineLimit: 1024 * 1024,
      cssCodeSplit: false,
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks: undefined,
        },
      },
    },
  };
});

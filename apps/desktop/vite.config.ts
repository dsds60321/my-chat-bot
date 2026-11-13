import {defineConfig} from 'vite';

export default defineConfig({
    server: {port: 1420, strictPort: true, watch: {ignored: ['**/src-tauri/**']}},
    build: {rollupOptions: {input: {main: 'index.html', answer: 'answer.html'}}},
    clearScreen: false,
});

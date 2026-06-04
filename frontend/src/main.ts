import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';

import App from './App.vue';
import { initRouter } from './router';
import './styles/variables.css';
import './styles/layout.css';
import './styles/components.css';

initRouter();

createApp(App)
  .use(createPinia())
  .use(ElementPlus)
  .mount('#app');

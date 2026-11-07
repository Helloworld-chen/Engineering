import {createApp} from 'vue';
import {createPinia} from 'pinia';
import {ElButton,ElConfigProvider,ElDialog,ElForm,ElFormItem,ElInput,ElPagination,ElSkeleton} from 'element-plus';
import 'element-plus/es/components/base/style/css';
import 'element-plus/es/components/button/style/css';
import 'element-plus/es/components/dialog/style/css';
import 'element-plus/es/components/form/style/css';
import 'element-plus/es/components/input/style/css';
import 'element-plus/es/components/pagination/style/css';
import 'element-plus/es/components/skeleton/style/css';
import '@fontsource/geist/latin-400.css';
import '@fontsource/geist/latin-500.css';
import '@fontsource/geist/latin-600.css';
import './style.css';
import App from './App.vue';
import router from './router';

const app=createApp(App).use(createPinia()).use(router);
for(const component of [ElButton,ElConfigProvider,ElDialog,ElForm,ElFormItem,ElInput,ElPagination,ElSkeleton]){
 app.use(component);
}
app.mount('#app');

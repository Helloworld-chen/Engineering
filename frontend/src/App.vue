<script setup lang="ts">
import {ref,watch,onMounted,onUnmounted} from 'vue';
import {useRouter} from 'vue-router';
import {PhStorefront,PhList,PhX,PhBell} from '@phosphor-icons/vue';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import {useSession} from './stores/session';
import {money} from './api/client';

const session=useSession(),router=useRouter(),menu=ref(false),error=ref('');
let events:EventSource|undefined;
let lastConnectionCheck=-Infinity;
watch(()=>session.user?.id,(id,previous)=>{
 events?.close();
 lastConnectionCheck=-Infinity;
 if(id){
  events=new EventSource('/api/v1/events');
  events.addEventListener('changed',refresh);
  events.onopen=refresh;
  events.onerror=()=>{
   const now=performance.now();
   if(now-lastConnectionCheck<5000)return;
   lastConnectionCheck=now;
   void session.verify().catch(()=>{ /* EventSource 会尝试重新连接。 */ });
  };
 }else if(previous&&(router.currentRoute.value.meta.auth||router.currentRoute.value.meta.role)){
  void router.replace({path:'/login',query:{redirect:router.currentRoute.value.fullPath}});
 }
},{immediate:true});

const unregister=router.afterEach(()=>{menu.value=false;error.value=''});
onMounted(()=>window.addEventListener('online',refresh));
onUnmounted(()=>{
 events?.close();
 unregister();
 window.removeEventListener('online',refresh);
});

function refresh(){
 window.dispatchEvent(new Event('market:changed'));
 void session.refresh().catch(()=>{ /* 页面请求独立展示读取错误。 */ });
}

async function logout(){
 try{
  await session.logout();
  await router.push('/');
 }catch(failure){
  error.value=failure instanceof Error?failure.message:'退出失败，请重试';
 }
}
</script>

<template>
 <el-config-provider :locale="zhCn">
  <a href="#main" class="skip-link">跳转到主要内容</a>
  <div class="site-wrap">
   <header class="topbar" @keydown.esc="menu=false">
    <RouterLink class="brand" to="/"><PhStorefront :size="24" weight="bold" aria-hidden="true"/><span>校园集市<small>CAMPUS MARKET</small></span></RouterLink>
    <button class="menu-toggle" :aria-expanded="menu" :aria-label="menu?'关闭导航':'展开导航'" aria-controls="primary-navigation" @click="menu=!menu">
     <PhX v-if="menu" :size="24" aria-hidden="true"/><PhList v-else :size="24" aria-hidden="true"/>
    </button>
    <nav id="primary-navigation" :class="{open:menu}" aria-label="主导航">
     <RouterLink to="/">浏览商品</RouterLink>
     <template v-if="session.user">
      <RouterLink v-if="session.user.role==='USER'" to="/my/products">我的商品</RouterLink>
      <RouterLink v-if="session.user.role==='USER'" to="/orders">我的订单</RouterLink>
      <RouterLink v-if="session.user.role!=='ADMIN'" to="/cases">{{session.user.role==='SUPPORT'?'客服工作台':'售后记录'}}</RouterLink>
      <RouterLink v-if="session.user.role==='ADMIN'" to="/moderation">商品审核</RouterLink>
      <RouterLink v-if="session.user.role==='ADMIN'" to="/reports">举报处理</RouterLink>
      <RouterLink to="/notifications" aria-label="通知"><PhBell :size="20" aria-hidden="true"/></RouterLink>
      <RouterLink to="/profile" :aria-label="'个人中心 · '+session.user.displayName">{{session.user.displayName}}</RouterLink>
      <button type="button" class="text-button" @click="logout">退出</button>
     </template>
     <template v-else>
      <RouterLink to="/login">登录</RouterLink><RouterLink to="/register" class="nav-primary">注册</RouterLink>
     </template>
    </nav>
   </header>
   <div v-if="session.demo" class="demo-banner">
    <span class="dot" aria-hidden="true"/>课程演示 · 模拟资金与物流
    <span class="banner-detail">协作模块当前使用可替换演示适配</span>
    <span v-if="session.user" class="balance">余额 {{money(session.balance||0)}}</span>
   </div>
   <p v-if="error" class="error" role="alert">{{error}}</p>
   <main id="main" tabindex="-1"><RouterView :key="$route.fullPath"/></main>
   <footer><span>校园集市 · 软件工程课程项目</span><span>本地演示数据，不发生真实资金流转</span></footer>
  </div>
 </el-config-provider>
</template>

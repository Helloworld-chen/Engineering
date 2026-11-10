<script setup lang="ts">
import {computed,ref,watch,onMounted,onUnmounted} from 'vue';
import {useRouter} from 'vue-router';
import {PhStorefront,PhList,PhX,PhBell} from '@phosphor-icons/vue';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import {useSession} from './stores/session';
import {useNotifications} from './stores/notifications';
import NotificationAlerts from './components/NotificationAlerts.vue';
import {money} from './api/client';
import StaffNavigation from './components/StaffNavigation.vue';
import {isStaff,homeFor,canVisit} from './lib/access';

const session=useSession(),router=useRouter(),menu=ref(false),error=ref('');
const notifications=useNotifications();
const staff=computed(()=>isStaff(session.user?.role));
const home=computed(()=>homeFor(session.user?.role));
let loggingOut=false;
let events:EventSource|undefined;
let lastConnectionCheck=-Infinity;
watch(()=>session.user?.id,(id,previous)=>{
 events?.close();
 void notifications.setUser(id||null);
 lastConnectionCheck=-Infinity;
 if(id){
  if(previous&&!canVisit(session.user?.role,router.currentRoute.value.path))void router.replace(homeFor(session.user?.role));
  const connection=new EventSource('/api/v1/events');events=connection;
  const active=()=>events===connection&&session.user?.id===id;
  connection.addEventListener('changed',event=>{if(active())refresh((event as MessageEvent).lastEventId)});
  connection.addEventListener('business-changed',()=>{if(active())refreshBusiness()});
  connection.addEventListener('notifications-read',()=>{if(active())void notifications.sync(false)});
  connection.onopen=()=>{if(active())refresh()};
  connection.onerror=()=>{
   if(!active())return;
   const now=performance.now();
   if(now-lastConnectionCheck<5000)return;
   lastConnectionCheck=now;
   void session.verify().catch(()=>{ /* EventSource 会尝试重新连接。 */ });
  };
 }else if(!loggingOut&&previous&&(router.currentRoute.value.meta.auth||router.currentRoute.value.meta.role)){
  void router.replace({path:'/login',query:{redirect:router.currentRoute.value.fullPath}});
 }
},{immediate:true,flush:'sync'});

const unregister=router.afterEach(()=>{menu.value=false;error.value=''});
function recover(){void session.verify().then(()=>{window.dispatchEvent(new Event('market:changed'));return notifications.sync()}).catch(()=>{})}
function visible(){if(document.visibilityState==='visible')recover()}
onMounted(()=>{
 window.addEventListener('online',recover);
 window.addEventListener('focus',recover);
 document.addEventListener('visibilitychange',visible);
});
onUnmounted(()=>{
 events?.close();
 unregister();
  void notifications.setUser(null);
  window.removeEventListener('online',recover);
  window.removeEventListener('focus',recover);
  document.removeEventListener('visibilitychange',visible);
});

function refresh(eventId?:string){
 refreshBusiness();
 void notifications.sync(true,eventId);
}
function refreshBusiness(){
 window.dispatchEvent(new Event('market:changed'));
 void session.refresh().catch(()=>{ /* 页面请求独立展示读取错误。 */ });
}

async function logout(){
 loggingOut=true;
 try{
  await session.logout();
  await router.push('/');
 }catch(failure){
  error.value=failure instanceof Error?failure.message:'退出失败，请重试';
 }finally{
  loggingOut=false;
 }
}
</script>

<template>
 <el-config-provider :locale="zhCn">
  <a href="#main" class="skip-link">跳转到主要内容</a>
  <div :class="staff?'staff-wrap':'site-wrap'">
   <StaffNavigation v-if="staff" v-model="menu" @logout="logout"/>
   <header class="topbar" @keydown.esc="menu=false">
    <RouterLink class="brand" :to="home"><PhStorefront :size="24" weight="bold" aria-hidden="true"/><span>{{staff?(session.user?.role==='ADMIN'?'管理员工作台':'客服工作台'):'校园集市'}}<small>{{staff?'CAMPUS MARKET · 工作空间':'CAMPUS MARKET'}}</small></span></RouterLink>
    <button class="menu-toggle" :aria-expanded="menu" :aria-label="menu?'关闭导航':'展开导航'" :aria-controls="staff?'staff-navigation':'primary-navigation'" @click="menu=!menu">
     <PhX v-if="menu" :size="24" aria-hidden="true"/><PhList v-else :size="24" aria-hidden="true"/>
    </button>
    <nav v-if="!staff" id="primary-navigation" :class="{open:menu}" aria-label="主导航">
     <RouterLink to="/">浏览商品</RouterLink>
     <template v-if="session.user">
      <RouterLink v-if="session.user.role==='USER'" to="/my/products">我的商品</RouterLink>
      <RouterLink v-if="session.user.role==='USER'" to="/orders">我的订单</RouterLink>
      <RouterLink v-if="session.user.role!=='ADMIN'" to="/cases">{{session.user.role==='SUPPORT'?'客服工作台':'售后记录'}}</RouterLink>
      <RouterLink v-if="session.user.role==='ADMIN'" to="/moderation">商品审核</RouterLink>
      <RouterLink v-if="session.user.role==='ADMIN'" to="/reports">举报处理</RouterLink>
      <RouterLink to="/profile" :aria-label="'个人中心 · '+session.user.displayName">{{session.user.displayName}}</RouterLink>
      <button type="button" class="text-button" @click="logout">退出</button>
     </template>
     <template v-else>
      <RouterLink to="/login">登录</RouterLink><RouterLink to="/register" class="nav-primary">注册</RouterLink>
     </template>
    </nav>
    <RouterLink v-if="staff" class="staff-profile" to="/profile">{{session.user?.displayName}}</RouterLink>
    <RouterLink v-if="session.user" class="notification-bell" to="/notifications" aria-label="通知" aria-describedby="notification-count">
     <PhBell :size="20" aria-hidden="true"/>
     <span v-if="notifications.badge" class="notification-badge" aria-hidden="true">{{notifications.badge}}</span>
     <span v-else-if="notifications.unreadCount===null" class="notification-pending" aria-hidden="true">{{notifications.error?'?':'…'}}</span>
     <span id="notification-count" class="sr-only">{{notifications.unreadCount===null?(notifications.error?'未读数量暂不可用':'正在读取未读数量'):`${notifications.unreadCount}条未读通知`}}</span>
    </RouterLink>
   </header>
   <div v-if="session.user && notifications.error&&router.currentRoute.value.path!=='/notifications'" class="notice notification-error" aria-live="polite">
    <span>{{notifications.error}}</span><button class="button" @click="notifications.sync()">重试通知同步</button>
   </div>
   <NotificationAlerts/>
   <div v-if="session.demo" class="demo-banner">
    <span class="dot" aria-hidden="true"/>课程演示 · 模拟资金与物流
    <span class="banner-detail">协作模块当前使用可替换演示适配</span>
    <span v-if="session.user?.role==='USER'" class="balance">余额 {{money(session.balance||0)}}</span>
   </div>
   <p v-if="error" class="error" role="alert">{{error}}</p>
   <main id="main" tabindex="-1"><RouterView :key="['/moderation','/reports','/cases'].includes($route.path)?$route.path:$route.fullPath"/></main>
   <footer><span>校园集市 · 软件工程课程项目</span><span>本地演示数据，不发生真实资金流转</span></footer>
  </div>
 </el-config-provider>
</template>

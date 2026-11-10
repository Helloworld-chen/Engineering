<script setup lang="ts">
import {computed} from 'vue';
import {useRoute} from 'vue-router';
import {PhSquaresFour,PhClipboardText,PhFlag,PhLifebuoy,PhBell,PhUserCircle,PhSignOut} from '@phosphor-icons/vue';
import {useSession} from '../stores/session';
const open=defineModel<boolean>({default:false});defineEmits<{logout:[]}>();
const session=useSession(),route=useRoute();
const admin=computed(()=>session.user?.role==='ADMIN');
const title=computed(()=>admin.value?'管理员工作台':'客服工作台');
const links=computed(()=>admin.value?[
 {to:'/admin',label:'工作台',icon:PhSquaresFour},{to:'/moderation',label:'商品审核',icon:PhClipboardText},{to:'/reports',label:'举报处理',icon:PhFlag}
]:[{to:'/support',label:'工作台',icon:PhSquaresFour},{to:'/cases',label:'售后处理',icon:PhLifebuoy}]);
</script>
<template>
 <aside class="staff-sidebar" aria-label="岗位导航">
  <RouterLink class="brand" :to="admin?'/admin':'/support'"><span>校园集市<small>{{title}}</small></span></RouterLink>
  <p class="muted">{{admin?'商品审核与举报处理':'售后审核与争议处理'}}</p>
  <nav class="staff-links" aria-label="工作导航"><RouterLink v-for="link in links" :key="link.to" :to="link.to" :class="{selected:route.path===link.to||route.path.startsWith(link.to+'/')}"><component :is="link.icon" :size="20"/>{{link.label}}</RouterLink></nav>
  <nav class="staff-links staff-account" aria-label="账号导航"><RouterLink to="/notifications" aria-label="通知中心"><PhBell :size="20"/>通知</RouterLink><RouterLink to="/profile"><PhUserCircle :size="20"/>账号资料</RouterLink><button @click="$emit('logout')"><PhSignOut :size="20"/>退出</button></nav>
 </aside>
 <el-drawer v-model="open" :title="title" direction="ltr" size="min(300px, 90vw)" destroy-on-close>
  <nav id="staff-navigation" class="staff-links" aria-label="工作导航"><RouterLink v-for="link in links" :key="link.to" :to="link.to" :class="{selected:route.path===link.to||route.path.startsWith(link.to+'/')}" @click="open=false"><component :is="link.icon" :size="20"/>{{link.label}}</RouterLink><RouterLink to="/notifications" aria-label="通知中心" @click="open=false">通知</RouterLink><RouterLink to="/profile" @click="open=false">账号资料</RouterLink><button @click="open=false;$emit('logout')">退出</button></nav>
 </el-drawer>
</template>

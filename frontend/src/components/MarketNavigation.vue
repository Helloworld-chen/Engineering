<script setup lang="ts">
import {useSession} from '../stores/session';
defineProps<{mobile?:boolean}>();
defineEmits<{logout:[]}>();
const session=useSession();
</script>
<template>
    <nav :id="mobile?'primary-navigation-mobile':'primary-navigation'" :class="{'market-mobile-links':mobile}" aria-label="主导航">
     <RouterLink to="/">浏览商品</RouterLink>
     <template v-if="session.user">
      <RouterLink v-if="session.user.role==='USER'" to="/my/products">我的商品</RouterLink>
      <RouterLink v-if="session.user.role==='USER'" to="/orders">我的订单</RouterLink>
      <RouterLink v-if="session.user.role!=='ADMIN'" to="/cases">{{session.user.role==='SUPPORT'?'客服工作台':'售后记录'}}</RouterLink>
      <RouterLink v-if="session.user.role==='ADMIN'" to="/moderation">商品审核</RouterLink>
      <RouterLink v-if="session.user.role==='ADMIN'" to="/reports">举报处理</RouterLink>
      <RouterLink to="/profile" :aria-label="'个人中心 · '+session.user.displayName">{{session.user.displayName}}</RouterLink>
      <button type="button" class="text-button" @click="$emit('logout')">退出</button>
     </template>
     <template v-else>
      <RouterLink to="/login">登录</RouterLink><RouterLink to="/register" class="nav-primary">注册</RouterLink>
     </template>
    </nav>
</template>

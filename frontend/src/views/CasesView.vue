<script setup lang="ts">
import {computed,ref} from 'vue';
import {PhArrowRight} from '@phosphor-icons/vue';
import {api,date,type Case} from '../api/client';
import {label} from '../lib/labels';
import {useLoad} from '../lib/load';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
const session=useSession(),activeOnly=ref(session.user?.role==='SUPPORT');
const {data,loading,error,reload}=useLoad(()=>api<{items:Case[]}>('/cases'));
const items=computed(()=>data.value?.items.filter(item=>!activeOnly.value||item.status==='SUPPORT_REVIEW')||[]);
</script>
<template>
 <div class="page-heading"><div><h1>{{session.user?.role==='SUPPORT'?'客服工作台':'售后记录'}}</h1><p>查看退款、投诉、申诉及退货进度。</p></div></div>
 <div v-if="session.user?.role==='SUPPORT'" class="tabs list-tabs"><button :aria-pressed="activeOnly" @click="activeOnly=true">待客服处理</button><button :aria-pressed="!activeOnly" @click="activeOnly=false">全部售后</button></div>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!items.length" class="panel empty"><h2>{{activeOnly?'暂无待客服处理的售后':'暂无售后记录'}}</h2><p>{{activeOnly?'新转交的售后会出现在这里。':'在订单详情发起售后后，可以在这里跟进。'}}</p><RouterLink v-if="!activeOnly&&session.user?.role==='USER'" class="button" to="/orders">查看订单</RouterLink></div>
  <div v-else class="record-list">
   <RouterLink v-for="item in items" :key="item.id" class="record record-link" :to="'/cases/'+item.id">
    <div class="record-body stack tight"><h2 class="record-title">{{label(item.kind)}}</h2><p class="clamp">{{item.reason}}</p><span class="muted">创建于 {{date(item.createdAt)}}</span></div>
    <div class="row record-actions"><StatusBadge :value="item.status"/><PhArrowRight :size="18" aria-hidden="true"/></div>
   </RouterLink>
  </div>
 </PageState>
</template>

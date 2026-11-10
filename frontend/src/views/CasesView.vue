<script setup lang="ts">
import {computed} from 'vue';
import {PhArrowRight} from '@phosphor-icons/vue';
import {api,date,type Case} from '../api/client';
import {label} from '../lib/labels';
import {useWorkLoad} from '../lib/workLoad';
import {ordered,pageItems,useListQuery,caseGroup} from '../lib/worklist';
import WorkPagination from '../components/WorkPagination.vue';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
const session=useSession();
const {query,set,route}=useListQuery(),group=query('group',session.user?.role==='SUPPORT'?'pending':'all'),kind=query('kind','all'),q=query('q'),page=query('page','1');
const {data,loading,error,reload}=useWorkLoad(()=>api<{items:Case[]}>('/cases'));
const result=computed(()=>pageItems(ordered((data.value?.items||[]).filter(c=>(!['pending','active','closed'].includes(group.value)||caseGroup(c.status)===group.value)&&(kind.value==='all'||c.kind===kind.value)&&(!q.value||c.id.includes(q.value.trim())||c.orderId.includes(q.value.trim()))),group.value==='pending'),page.value));
</script>
<template>
 <div class="page-heading"><div><h1>{{session.user?.role==='SUPPORT'?'售后处理':'售后记录'}}</h1><p>查看退款、投诉、申诉及退货进度。</p></div><button class="button" @click="reload()">刷新列表</button></div>
 <div class="work-toolbar">
  <label class="field-label">处理状态<select :value="group" @change="set({group:($event.target as HTMLSelectElement).value})"><option value="pending">待客服处理</option><option value="active">其他处理中</option><option value="closed">已关闭</option><option value="all">全部售后</option></select></label>
  <label class="field-label">售后类型<select :value="kind" @change="set({kind:($event.target as HTMLSelectElement).value})"><option value="all">全部类型</option><option value="REFUND">退款</option><option value="COMPLAINT">投诉</option></select></label>
  <label class="field-label">搜索售后<input type="search" :value="q" placeholder="售后单号或订单号" @input="set({q:($event.target as HTMLInputElement).value})"/></label>
 </div>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!result.total" class="panel empty"><h2>暂无符合条件的售后记录</h2><p>可调整筛选条件，或等待新的售后申请。</p></div>
  <div v-else class="record-list">
   <RouterLink v-for="item in result.items" :key="item.id" class="record record-link" :to="{path:'/cases/'+item.id,query:{from:route.fullPath}}">
    <div class="record-body stack tight"><h2 class="record-title">{{label(item.kind)}}</h2><p class="clamp">{{item.reason}}</p><span class="muted work-id">售后单 {{item.id}} · 订单 {{item.orderId}}</span><span class="muted">创建于 {{date(item.createdAt)}}</span></div>
    <div class="row record-actions"><StatusBadge :value="item.status"/><PhArrowRight :size="18" aria-hidden="true"/></div>
   </RouterLink>
  </div>
  <WorkPagination v-bind="result" @change="set({page:String($event)})"/>
 </PageState>
</template>

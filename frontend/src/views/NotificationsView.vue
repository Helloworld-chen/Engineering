<script setup lang="ts">
import {computed,ref} from 'vue';
import {PhBell} from '@phosphor-icons/vue';
import {api,date,type Notification} from '../api/client';
import {useLoad} from '../lib/load';
import {useAction} from '../lib/action';
import PageState from '../components/PageState.vue';
const {data,loading,error,reload}=useLoad(()=>api<{items:Notification[]}>('/notifications'));
const {busy,error:actionError,run}=useAction();
const unread=ref(false);
const items=computed(()=>data.value?.items.filter(item=>!unread.value||!item.isRead)||[]);
function read(item:Notification){
 void run(async()=>{await api('/notifications/'+item.id+'/read','POST',{});await reload()});
}
</script>
<template>
 <div class="page-heading"><div><h1>通知</h1><p>查看订单、售后和商品审核进展。</p></div></div>
 <div class="tabs list-tabs"><button :aria-pressed="!unread" @click="unread=false">全部通知</button><button :aria-pressed="unread" @click="unread=true">未读通知</button></div>
 <p v-if="actionError" class="error notice" role="alert">{{actionError}}</p>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!items.length" class="panel empty"><h2>{{unread?'暂无未读通知':'暂无通知'}}</h2><p>业务有新进展时，消息会出现在这里。</p></div>
  <div v-else class="record-list">
   <article v-for="item in items" :key="item.id" class="record">
    <PhBell :size="24" :weight="item.isRead?'thin':'fill'" aria-hidden="true"/>
    <div class="record-body stack tight"><h2 class="record-title">{{item.message}}</h2><p class="muted">{{date(item.createdAt)}} · {{item.isRead?'已读':'未读'}}</p></div>
    <button v-if="!item.isRead" class="button record-actions" :disabled="busy" @click="read(item)">标为已读</button>
   </article>
  </div>
 </PageState>
</template>

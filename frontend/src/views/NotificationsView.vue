<script setup lang="ts">
import {computed,ref,onMounted} from 'vue';
import {PhBell} from '@phosphor-icons/vue';
import {date,type Notification} from '../api/client';
import {useNotifications} from '../stores/notifications';
import {useAction} from '../lib/action';
import PageState from '../components/PageState.vue';
const notifications=useNotifications();
onMounted(()=>void notifications.sync());
const {busy,error:actionError,run}=useAction();
const unread=ref(false);
const items=computed(()=>notifications.items.filter(item=>!unread.value||!item.isRead));
function read(item:Notification){
 void run(()=>notifications.markRead(item.id));
}
</script>
<template>
 <div class="page-heading"><div><h1>通知</h1><p>查看订单、售后和商品审核进展。列表展示最近100条，未读数量包含全部通知。</p></div><button class="button" :disabled="busy||!notifications.unreadCount||!!notifications.error" @click="run(()=>notifications.markAllRead())">全部标为已读</button></div>
 <div class="tabs list-tabs"><button :aria-pressed="!unread" @click="unread=false">全部通知</button><button :aria-pressed="unread" @click="unread=true">未读通知</button></div>
 <p v-if="actionError" class="error notice" role="alert">{{actionError}}</p>
 <PageState :loading="notifications.loading" :error="notifications.error" @retry="notifications.sync()">
  <div v-if="!items.length" class="panel empty"><h2>{{unread?(notifications.unreadCount?'最近100条中没有未读通知':'暂无未读通知'):'暂无通知'}}</h2><p>{{unread && notifications.unreadCount?'较早的通知仍有未读，可使用全部标为已读。':'业务有新进展时，消息会出现在这里。'}}</p></div>
  <div v-else class="record-list">
   <article v-for="item in items" :key="item.id" class="record">
    <PhBell :size="24" :weight="item.isRead?'thin':'fill'" aria-hidden="true"/>
    <div class="record-body stack tight"><h2 class="record-title">{{item.message}}</h2><p class="muted">{{date(item.createdAt)}} · {{item.isRead?'已读':'未读'}}</p></div>
    <button v-if="!item.isRead" class="button record-actions" :disabled="busy" @click="read(item)">标为已读</button>
   </article>
  </div>
 </PageState>
</template>

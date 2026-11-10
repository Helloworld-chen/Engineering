<script setup lang="ts">
import {onUnmounted,ref,watch} from 'vue';
import {PhX} from '@phosphor-icons/vue';
import {useNotifications} from '../stores/notifications';
const notifications=useNotifications(),hovered=ref(false),focused=ref(false);
let timer:ReturnType<typeof setTimeout>|undefined,remaining=8000,started=0;
function stop(){
 if(timer!==undefined){clearTimeout(timer);timer=undefined;remaining=Math.max(0,remaining-(Date.now()-started))}
}
function resume(){
 stop();
 if(!notifications.notice||hovered.value||focused.value)return;
 started=Date.now();timer=setTimeout(()=>{timer=undefined;notifications.dismiss()},remaining);
}
watch(()=>notifications.notice?.key,()=>{
 stop();remaining=8000;
 if(!notifications.notice){hovered.value=false;focused.value=false}
 resume();
},{flush:'sync'});
watch([hovered,focused],resume,{flush:'sync'});
function focusOut(event:FocusEvent){focused.value=(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node|null)}
onUnmounted(stop);
</script>
<template>
 <div class="notification-announcer" role="status" aria-live="polite" aria-atomic="true">
  <aside v-if="notifications.notice" class="notification-toast" aria-label="新消息提醒"
   @mouseenter="hovered=true" @mouseleave="hovered=false" @focusin="focused=true" @focusout="focusOut">
   <div class="row spread"><strong>{{notifications.notice.multiple?'收到多条新消息':'收到新消息'}}</strong><button class="text-button" aria-label="关闭消息提醒" @click="notifications.dismiss"><PhX :size="20" aria-hidden="true"/></button></div>
   <p>{{notifications.notice.message}}</p>
   <RouterLink class="button" to="/notifications" @click="notifications.dismiss">查看通知</RouterLink>
  </aside>
 </div>
</template>

<script setup lang="ts">
import {ref} from 'vue';
import {api,date,money} from '../api/client';
import {label} from '../lib/labels';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
const session=useSession();
const displayName=ref(session.user?.displayName||''),seconds=ref('11'),confirmation=ref(''),resetDialog=ref(false),serverTime=ref('');
const profile=useAction(),demo=useAction();
function save(){
 void profile.run(async()=>{
  if(!displayName.value.trim())throw new Error('请填写昵称');
  await api('/profile','PATCH',{displayName:displayName.value.trim()});await session.refresh();
 },'昵称已更新。');
}
function advance(){
 void demo.run(async()=>{
  const value=Number(seconds.value);
  if(!Number.isInteger(value)||value<=0||value>31536000)throw new Error('请输入 1 至 31536000 的整数秒数');
  const result=await api<{serverTime:string,ready:boolean}>('/demo/advance','POST',{millis:value*1000});
  serverTime.value=result.serverTime;
  if(!result.ready)throw new Error('演示时钟已推进，后端正在处理到期任务。');
  window.dispatchEvent(new Event('market:changed'));
 },'演示时钟已推进。');
}
function reset(){
 void demo.run(async()=>{
  if(confirmation.value!=='RESET')throw new Error('请输入 RESET 确认重置');
  await api('/demo/reset','POST',{confirmation:confirmation.value});resetDialog.value=false;serverTime.value='';await session.refresh();
  displayName.value=session.user?.displayName||'';window.dispatchEvent(new Event('market:changed'));
 },'演示数据已重置。');
}
</script>
<template>
 <div class="page-heading"><div><h1>个人中心</h1><p>查看账号身份和模拟余额。</p></div></div>
 <div class="order-grid">
  <section class="panel stack">
   <h2>账号资料</h2>
   <dl class="facts"><dt>用户名</dt><dd>{{session.user?.username}}</dd><dt>身份</dt><dd>{{label(session.user?.role||'')}}</dd><dt>模拟余额</dt><dd><strong>{{money(session.balance||0)}}</strong></dd></dl>
   <form class="stack section-divider" @submit.prevent="save">
    <label class="field-label">昵称<input v-model="displayName" required maxlength="80"/></label>
    <p v-if="profile.error.value" class="error" role="alert">{{profile.error.value}}</p>
    <p v-if="profile.success.value" class="success" role="status">{{profile.success.value}}</p>
    <div><button class="button primary" :disabled="profile.busy.value" type="submit">{{profile.busy.value?'正在保存…':'保存昵称'}}</button></div>
   </form>
  </section>
  <aside class="panel stack"><h2>账号记录</h2><RouterLink class="related-link" to="/notifications">查看通知 →</RouterLink><RouterLink class="related-link" to="/reports">{{session.user?.role==='ADMIN'?'举报处理':'我的举报'}} →</RouterLink></aside>
 </div>
 <section v-if="session.demo&&session.user?.role==='ADMIN'" class="panel stack demo-controls">
  <div class="eyebrow">仅课程演示</div><h2>演示控制</h2><p class="muted">推进后端业务时钟会处理到期订单、物流和售后任务。</p>
  <form class="row control-row" @submit.prevent="advance"><label class="field-label">推进时间（秒）<input v-model="seconds" type="number" min="1" max="31536000" required/></label><button class="button primary" type="submit" :disabled="demo.busy.value">推进演示时钟</button></form>
  <p v-if="serverTime" class="muted">当前演示时间 · {{date(serverTime)}}</p>
  <p v-if="demo.error.value&&!resetDialog" class="error" role="alert">{{demo.error.value}}</p>
  <p v-if="demo.success.value" class="success" role="status">{{demo.success.value}}</p>
  <div class="section-divider"><button class="button danger" :disabled="demo.busy.value" @click="confirmation='';demo.error.value='';resetDialog=true">重置演示数据</button></div>
 </section>
 <el-dialog v-model="resetDialog" title="重置演示数据" width="min(480px, calc(100% - 32px))" :close-on-click-modal="false" :show-close="!demo.busy.value" :close-on-press-escape="!demo.busy.value">
  <form class="stack" @submit.prevent="reset">
   <p>此操作会清除演示期间新增的商品、订单、售后与通知，并恢复种子数据。</p>
   <label class="field-label">输入 RESET 确认<input v-model="confirmation" required autocomplete="off"/></label>
   <p v-if="demo.error.value" class="error" role="alert">{{demo.error.value}}</p>
   <div class="row end"><button type="button" class="button" :disabled="demo.busy.value" @click="resetDialog=false">返回</button><button class="button danger" type="submit" :disabled="demo.busy.value||confirmation!=='RESET'">确认重置</button></div>
  </form>
 </el-dialog>
</template>

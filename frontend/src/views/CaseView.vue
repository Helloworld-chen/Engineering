<script setup lang="ts">
import {computed,ref} from 'vue';
import {useRoute} from 'vue-router';
import {PhClock,PhWallet} from '@phosphor-icons/vue';
import {api,date,money,type Case,type Order} from '../api/client';
import {label} from '../lib/labels';
import {useLoad} from '../lib/load';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
import Countdown from '../components/Countdown.vue';
import ActionDialog from '../components/ActionDialog.vue';
const route=useRoute(),session=useSession(),id=String(route.params.id);
const {data,loading,error,reload}=useLoad(async()=>{
 const {items}=await api<{items:Case[]}>('/cases');
 const item=items.find(value=>value.id===id);
 if(!item)throw new Error('当前账号无法读取此售后记录。');
 const order=await api<Order>('/orders/'+item.orderId);
 return {item:order.cases.find(value=>value.id===id)||item,order};
});
const {busy,error:actionError,success,run}=useAction();
const dialog=ref(false),selection=ref('');
const actions:Record<string,{title:string,description:string}>={
 REFUND:{title:'同意退款',description:'系统将把暂管货款退回买家的模拟余额。'},
 REJECT:{title:'驳回申请',description:'请说明驳回原因，交易双方可以查看处理结果。'},
 APPEAL:{title:'提交申诉',description:'申请将转交客服，货款继续由平台暂管。'},
 WITHDRAW:{title:'撤回退款申请',description:'撤回后订单恢复待发货。'},
 APPROVE_RETURN:{title:'批准退货',description:'买家需要寄回商品并提交凭证，验收后才进入退款处理。'},
 ACCEPT_RETURN:{title:'通过退货验收',description:'确认收到退货后，系统将把暂管货款退回买家。'},
 DISPUTE:{title:'提交验收异议',description:'售后将转交客服，货款继续由平台暂管。'},
 MORE_INFO:{title:'要求补充材料',description:'说明需要补充的内容，售后仍由客服跟进。'}
};
const available=computed(()=>{
 if(!data.value)return [];
 const {item,order}=data.value,user=session.user;
 if(user?.id===order.sellerId&&item.status==='SELLER_REVIEW')return ['REFUND','REJECT'];
 if(user?.id===order.buyerId&&item.status==='SELLER_REVIEW')return ['WITHDRAW'];
 if(user?.id===order.buyerId&&item.status==='BUYER_APPEAL')return ['APPEAL','WITHDRAW'];
 if(user?.id===order.sellerId&&item.status==='RETURN_INSPECTION')return ['ACCEPT_RETURN','DISPUTE'];
 if(user?.role==='SUPPORT'&&item.status==='SUPPORT_REVIEW'){
  const shipment=order.shipments.find(value=>value.direction==='RETURN');
  if(shipment)return shipment.status==='DELIVERED'?['REFUND','MORE_INFO']:['MORE_INFO'];
  return [item.kind==='REFUND'?'REFUND':'APPROVE_RETURN','REJECT','MORE_INFO'];
 }
 return [];
});
const description=computed(()=>selection.value==='REJECT'&&data.value?.item.status==='SELLER_REVIEW'?'卖家驳回后，买家可以在 24 小时内申诉。':actions[selection.value]?.description||'');
function open(key:string){selection.value=key;actionError.value='';dialog.value=true}
function submit(reason:string){
 void run(async()=>{
  await api('/cases/'+encodeURIComponent(id)+'/actions','POST',{action:selection.value,reason});
  dialog.value=false;await reload();await session.refresh();
 },'售后处理结果已保存。');
}
</script>
<template>
 <RouterLink class="back-link" to="/cases">← 返回售后记录</RouterLink>
 <div class="page-heading"><div><h1>售后详情</h1><p class="mono">售后单号 {{id}}</p></div><button class="button" :disabled="loading" @click="reload()">刷新售后</button></div>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="data" class="order-grid">
   <section class="panel stack">
    <div class="row spread"><h2>{{label(data.item.kind)}}申请</h2><StatusBadge :value="data.item.status"/></div>
    <p class="muted">提交于 {{date(data.item.createdAt)}}</p>
    <div class="stack tight section-divider"><h3>申请理由</h3><p class="preserve">{{data.item.reason}}</p></div>
    <div v-if="data.item.decision" class="stack tight section-divider"><h3>最近处理 · {{label(data.item.decision)}}</h3><p class="preserve">{{data.item.decisionReason}}</p></div>
    <div v-if="data.item.deadline" class="deadline-box"><div class="row"><PhClock :size="18" aria-hidden="true"/>当前处理剩余时间</div><Countdown :deadline="data.item.deadline" :server-time="data.order.serverTime"/><p class="muted">截止 {{date(data.item.deadline)}}</p></div>
    <p v-if="data.item.status==='SUPPORT_REVIEW'" class="notice muted">等待客服处理，货款继续由平台暂管。</p>
    <div v-if="available.length" class="row"><button v-for="(key,index) in available" :key="key" class="button" :class="{primary:index===0}" :disabled="busy" @click="open(key)">{{actions[key].title}}</button></div>
    <RouterLink v-if="data.order.allowedActions.includes('RETURN_SHIP')" :to="'/orders/'+data.order.id" class="button primary">前往订单提交退货凭证</RouterLink>
    <p v-if="success" class="success" role="status">{{success}}</p>
   </section>
   <aside class="panel stack"><h2>关联订单</h2><RouterLink class="record-title" :to="'/orders/'+data.order.id">{{data.order.product.title}} →</RouterLink><div class="row spread"><span class="row"><PhWallet :size="18"/> {{money(data.order.amount)}}</span><StatusBadge :value="data.order.fundsStatus"/></div><p v-if="data.order.receiptPaused" class="muted">自动收货已暂停。</p><RouterLink class="button" :to="'/orders/'+data.order.id">查看订单与物流凭证</RouterLink></aside>
  </div>
 </PageState>
 <ActionDialog v-model="dialog" :title="actions[selection]?.title||''" :description="description" requires-reason :busy="busy" :error="actionError" @confirm="submit"/>
</template>

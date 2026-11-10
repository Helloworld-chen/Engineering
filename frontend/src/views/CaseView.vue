<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref} from 'vue';
import {useRoute} from 'vue-router';
import {PhClock,PhWallet} from '@phosphor-icons/vue';
import {api,date,money,type Case,type Order} from '../api/client';
import {label} from '../lib/labels';
import {useWorkLoad} from '../lib/workLoad';
import {returnTo} from '../lib/access';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
import Countdown from '../components/Countdown.vue';
import ActionDialog from '../components/ActionDialog.vue';
const route=useRoute(),session=useSession(),id=String(route.params.id);
const back=computed(()=>returnTo(route.query.from,session.user?.role,session.user?.role==='SUPPORT'?'/cases?group=pending':'/cases',['/cases','/support']));
const orderLink=computed(()=>({path:'/orders/'+data.value?.order.id,query:session.user?.role==='SUPPORT'?{from:route.fullPath}:undefined}));
const {data,loading,error,reload}=useWorkLoad(async()=>{
 const {items}=await api<{items:Case[]}>('/cases');
 const item=items.find(value=>value.id===id);
 if(!item)throw new Error('当前账号无法读取此售后记录。');
 const order=await api<Order>('/orders/'+item.orderId);
 return {item:order.cases.find(value=>value.id===id)||item,order};
});
const {busy,error:actionError,success,run}=useAction();
type Message={id:string;senderId:string;role:string;content:string;createdAt:string};
const messages=ref<Message[]>([]),draft=ref(''),sending=ref(false),messageError=ref('');
const roleName:Record<string,string>={BUYER:'买家',SELLER:'卖家',SUPPORT:'客服'};
async function loadMessages(){
 try{const {items}=await api<{items:Message[]}>('/cases/'+encodeURIComponent(id)+'/messages');messages.value=items}
 catch{/* 列表读取失败时保留上一次成功结果 */}
}
function sendMessage(){
 const content=draft.value.trim();
 if(!content||sending.value)return;
 sending.value=true;messageError.value='';
 void api('/cases/'+encodeURIComponent(id)+'/messages','POST',{content})
  .then(()=>{draft.value='';return loadMessages()})
  .catch((failure:unknown)=>{messageError.value=failure instanceof Error?failure.message:'留言发送失败，请重试'})
  .finally(()=>{sending.value=false});
}
let poll:ReturnType<typeof setInterval>|undefined;
onMounted(()=>{void loadMessages();poll=setInterval(()=>{if(document.visibilityState!=='hidden')void loadMessages()},10000)});
onUnmounted(()=>{if(poll)clearInterval(poll)});
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
 <RouterLink class="back-link" :to="back">← {{session.user?.role==='SUPPORT'?(back.startsWith('/support')?'返回工作台':'返回售后处理'):'返回售后记录'}}</RouterLink>
 <div class="page-heading"><div><h1>售后详情</h1><p class="mono">售后单号 {{id}}</p></div><button class="button" :disabled="loading" @click="reload()">刷新售后</button></div>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="data" class="order-grid">
   <section class="panel stack">
    <div class="row spread"><h2>{{label(data.item.kind)}}申请</h2><StatusBadge :value="data.item.status"/></div>
    <p class="muted">提交于 {{date(data.item.createdAt)}}</p>
    <div class="stack tight section-divider"><h3>申请理由</h3><p class="preserve">{{data.item.reason}}</p></div>
    <div v-if="data.item.decision" class="stack tight section-divider"><h3>最近处理 · {{label(data.item.decision)}}</h3><p class="preserve">{{data.item.decisionReason}}</p></div>
    <div v-if="data.item.deadline" class="deadline-box"><div class="row"><PhClock :size="18" aria-hidden="true"/>当前处理剩余时间</div><Countdown :deadline="data.item.deadline" :server-time="data.order.serverTime"/><p class="muted">截止 {{date(data.item.deadline)}}</p></div>
    <p v-if="data.item.status==='SUPPORT_REVIEW'" class="notice muted">{{session.user?.role==='SUPPORT'?'请核对申请和凭证后提交处理决定。货款继续由平台暂管。':'等待客服处理，货款继续由平台暂管。'}}</p>
    <div v-if="available.length" class="row"><button v-for="(key,index) in available" :key="key" class="button" :class="{primary:index===0}" :disabled="busy" @click="open(key)">{{actions[key].title}}</button></div>
    <RouterLink v-if="data.order.allowedActions.includes('RETURN_SHIP')" :to="orderLink" class="button primary">前往订单提交退货凭证</RouterLink>
    <p v-if="success" class="success" role="status">{{success}}</p>
   </section>
   <aside class="panel stack"><h2>关联订单</h2><RouterLink class="record-title" :to="orderLink">{{data.order.product.title}} →</RouterLink><div class="row spread"><span class="row"><PhWallet :size="18"/> {{money(data.order.amount)}}</span><StatusBadge :value="data.order.fundsStatus"/></div><p v-if="data.order.receiptPaused" class="muted">自动收货已暂停。</p><RouterLink class="button" :to="orderLink">查看订单与物流凭证</RouterLink></aside>
  </div>
  <section v-if="data" class="panel stack section-divider">
   <h2>沟通记录</h2>
   <p class="muted">买卖双方与客服可在此留言，内容三方可见；页面每 10 秒自动刷新。</p>
   <div v-if="!messages.length" class="panel empty"><p>暂无留言</p></div>
   <article v-for="m in messages" :key="m.id" class="record">
    <div class="record-body stack tight">
     <div class="row spread"><strong>{{roleName[m.role]||m.role}}</strong><span class="muted">{{date(m.createdAt)}}</span></div>
     <p class="preserve">{{m.content}}</p>
    </div>
   </article>
   <label class="field-label">留言内容
    <input :value="draft" maxlength="500" placeholder="说明情况或补充材料，最多 500 字" @input="draft=($event.target as HTMLInputElement).value"/>
   </label>
   <p v-if="messageError" class="error" role="alert">{{messageError}}</p>
   <button class="button primary" :disabled="sending||!draft.trim()" @click="sendMessage">发送留言</button>
  </section>
 </PageState>
 <ActionDialog v-model="dialog" :title="actions[selection]?.title||''" :description="description" requires-reason :busy="busy" :error="actionError" @confirm="submit"/>
</template>

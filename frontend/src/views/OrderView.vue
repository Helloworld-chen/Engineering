<script setup lang="ts">
import {profileLink} from '../lib/userProfiles';
import {computed,ref} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {PhClock,PhPackage,PhWallet} from '@phosphor-icons/vue';
import {api,date,money,type Case,type Order} from '../api/client';
import {label} from '../lib/labels';
import {useWorkLoad} from '../lib/workLoad';
import {returnTo} from '../lib/access';
import {useAction} from '../lib/action';
import {useFieldErrors} from '../lib/fieldErrors';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import ProductPhoto from '../components/ProductPhoto.vue';
import StatusBadge from '../components/StatusBadge.vue';
import Countdown from '../components/Countdown.vue';
import ActionDialog from '../components/ActionDialog.vue';
import OrderReviews from '../components/OrderReviews.vue';
import UploadField from '../components/UploadField.vue';
const route=useRoute(),router=useRouter(),session=useSession();
const id=encodeURIComponent(String(route.params.id)),path='/orders/'+id;
const {data:order,loading,error,reload}=useWorkLoad(()=>api<Order>(path));
const {busy,error:actionError,success,run}=useAction();
const {fields,clear,flag,attributes}=useFieldErrors();
const dialog=ref(false),shipping=ref(false),selection=ref('');
const trackingNumber=ref(''),goods=ref<string[]>([]),packaging=ref<string[]>([]),goodsBusy=ref(false),packagingBusy=ref(false);
const actions:Record<string,{title:string,description:string,endpoint?:string,kind?:string}> = {
 PAY:{title:'付款',description:'确认后，系统将扣除模拟余额并由平台暂管货款。',endpoint:'payment'},
 CANCEL:{title:'取消订单',description:'取消后，系统将解除商品占用。',endpoint:'cancel'},
 CONFIRM:{title:'收货',description:'确认收到商品后，平台暂管的货款将结算给卖家。',endpoint:'receipt'},
 REQUEST_REFUND:{title:'申请退款',description:'退款受理后会暂停发货，等待卖家审核。',kind:'REFUND'},
 COMPLAIN:{title:'提交投诉',description:'投诉受理后会暂停自动收货和结算，等待客服处理。',kind:'COMPLAINT'},
 SHIP:{title:'发货',description:'确认寄出后，系统将在 10 秒后登记送达。',endpoint:'shipment'},
 RETURN_SHIP:{title:'寄出退货',description:'退货运费由买家承担。确认寄出后，系统将在 10 秒后登记退货送达。',endpoint:'return-shipment'}
};
const action=computed(()=>actions[selection.value]);
const returning=computed(()=>selection.value==='RETURN_SHIP');
const side=computed(()=>order.value?.sellerId===session.user?.id?'selling':'buying');
const support=computed(()=>session.user?.role==='SUPPORT');
const back=computed(()=>support.value?returnTo(route.query.from,'SUPPORT',order.value?.cases[0]?'/cases/'+order.value.cases[0].id:'/cases',['/cases','/users']):returnTo(route.query.from,session.user?.role,'/orders?side='+side.value,['/users','/orders']));
function caseLink(id:string){
 if(!support.value)return '/cases/'+id;
 const from=returnTo(router.resolve(back.value).query.from,'SUPPORT','/cases?group=pending',['/cases','/support']);
 return {path:'/cases/'+id,query:{from}};
}
const deadline=computed(()=>{
 const item=order.value;if(!item)return;
 if(item.status==='PENDING_PAYMENT')return {name:'付款剩余时间',time:item.payDeadline};
 const shipment=item.shipments.find(value=>value.status==='IN_TRANSIT');
 if(shipment)return {name:shipment.direction==='RETURN'?'退货预计送达':'商品预计送达',time:shipment.dueAt};
 const active=item.cases.find(value=>value.deadline);
 if(active)return {name:active.status==='RETURN_INSPECTION'?'退货验收剩余时间':'售后处理剩余时间',time:active.deadline};
 if(item.status==='DELIVERED')return {name:'自动收货剩余时间',time:item.receiptDeadline,paused:item.receiptPaused?item.receiptRemaining:null};
});
function open(key:string){
 selection.value=key;actionError.value='';clear();
 if(key==='SHIP'||key==='RETURN_SHIP'){trackingNumber.value='';goods.value=[];packaging.value=[];shipping.value=true}
 else dialog.value=true;
}
function submit(reason:string){
 const item=action.value;if(!item)return;
 void run(async()=>{
  if(item.kind){
   const result=await api<Case>('/cases','POST',{orderId:order.value!.id,kind:item.kind,reason});
   await router.push('/cases/'+result.id);return;
  }
  await api<Order>(path+'/'+item.endpoint,'POST',{});
  dialog.value=false;await reload();await session.refresh();
 },item.title==='付款'?'订单已付款，等待卖家发货。':item.title==='收货'?'已确认收货。':'订单已取消。');
}
function ship(){
 const item=action.value;if(!item)return;
 clear();
 actionError.value='';
 if(!trackingNumber.value.trim())return flag('tracking-number','请填写物流单号');
 if(!goods.value.length)return flag('goods-proof','请上传至少一张商品凭证');
 if(!packaging.value.length)return flag('packaging-proof','请上传至少一张包装凭证');
 if(goods.value.length+packaging.value.length>6)return flag('packaging-proof','商品与包装凭证合计最多六张');
 void run(async()=>{
  await api(path+'/'+item.endpoint,'POST',{trackingNumber:trackingNumber.value.trim(),goods:goods.value,packaging:packaging.value});
  shipping.value=false;await reload();
 },returning.value?'退货寄出凭证已保存。':'发货凭证已保存。');
}
</script>
<template>
 <RouterLink class="back-link" :to="back">← {{back.startsWith('/users/')?'返回用户资料':support?'返回售后详情':'返回我的订单'}}</RouterLink>
 <div class="page-heading"><div><h1>订单详情</h1><p v-if="order" class="mono">订单号 {{order.id}}</p></div><button class="button" :disabled="loading" @click="reload()">刷新订单</button></div>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="order" class="order-grid">
   <div class="stack">
    <section class="panel stack">
     <div class="row spread"><h2>订单进度</h2><StatusBadge :value="order.status"/></div>
     <div class="order-product">
      <ProductPhoto class="record-photo" :image="order.product.images[0]" :title="order.product.title" :category="order.product.category"/>
      <div><RouterLink class="record-title" :to="{path:'/products/'+order.productId,query:support?{from:route.fullPath}:undefined}">{{order.product.title}}</RouterLink><p class="muted">{{date(order.createdAt)}}</p></div>
     </div>
     <section class="section-divider stack" aria-label="交易双方">
      <h3>交易双方</h3>
      <dl class="facts order-parties">
       <dt>卖家</dt><dd data-party="seller"><strong>{{order.sellerName||order.product.sellerName||'未提供昵称'}}</strong><span v-if="session.user?.id===order.sellerId">（你）</span><p class="muted">用户编号 {{order.sellerId}}</p><RouterLink class="text-button" :to="profileLink(order.sellerId,'order',order.id,route.fullPath)">查看卖家资料 →</RouterLink></dd>
       <dt>买家</dt><dd data-party="buyer"><strong>{{order.buyerName||'未提供昵称'}}</strong><span v-if="session.user?.id===order.buyerId">（你）</span><p class="muted">用户编号 {{order.buyerId}}</p><RouterLink class="text-button" :to="profileLink(order.buyerId,'order',order.id,route.fullPath)">查看买家资料 →</RouterLink></dd>
      </dl>
     </section>
     <div v-if="deadline" class="deadline-box">
      <div class="row"><PhClock :size="18" aria-hidden="true"/>{{deadline.name}}</div>
      <Countdown :deadline="deadline.time" :server-time="order.serverTime" :paused="deadline.paused"/>
      <p v-if="deadline.paused==null&&deadline.time" class="muted">截止 {{date(deadline.time)}}</p>
     </div>
     <p v-if="order.receiptPaused||order.shippingBlocked||order.settlementBlocked" class="notice muted">售后处理中。{{order.shippingBlocked?' 发货已暂停。':''}}{{order.settlementBlocked?' 货款结算已暂停。':''}}</p>
     <p v-if="order.closeReason" class="muted">关闭原因 · {{label(order.closeReason)}}</p>
     <div v-if="!support&&order.allowedActions.length" class="row">
      <button v-for="(key,index) in order.allowedActions.filter(key=>actions[key]).sort((a,b)=>Number(a==='CANCEL')-Number(b==='CANCEL'))" :key="key" class="button" :class="{primary:index===0}" :disabled="busy" @click="open(key)">{{key==='CONFIRM'?'确认收货':actions[key].title}}</button>
     </div>
     <p v-if="success" class="success" role="status">{{success}}</p>
    </section>
    <OrderReviews v-if="order.status==='COMPLETED'" :order-id="order.id" :key="order.id"/>
    <section class="panel stack">
     <h2 class="row"><PhPackage :size="20" aria-hidden="true"/>物流与凭证</h2>
     <p v-if="!order.shipments.length" class="muted">暂未提交物流记录。</p>
     <article v-for="shipment in order.shipments" :key="shipment.id" class="stack section-divider">
      <div class="row spread"><h3>{{label(shipment.direction)}}</h3><span class="status">{{shipment.status==='DELIVERED'?'已送达':label(shipment.status)}}</span></div>
      <dl class="facts"><dt>物流单号</dt><dd>{{shipment.trackingNumber}}</dd><dt>提交时间</dt><dd>{{date(shipment.shippedAt)}}</dd><dt>送达时间</dt><dd>{{date(shipment.deliveredAt)}}</dd></dl>
      <div v-for="group in (['goods','packaging'] as const)" :key="group" class="stack tight">
       <h4>{{group==='goods'?'商品凭证':'包装凭证'}}</h4>
       <div class="photo-strip"><a v-for="(image,index) in shipment.evidence[group]" :key="image" :href="'/api/v1/files/'+image" target="_blank" rel="noopener"><img :src="'/api/v1/files/'+image" :alt="(group==='goods'?'商品凭证':'包装凭证')+' '+(index+1)"/></a></div>
      </div>
      <p class="muted">物流凭证已存档。</p>
     </article>
    </section>
   </div>
   <aside class="stack">
    <section class="panel stack" aria-labelledby="funds-title">
     <h2 id="funds-title" class="row"><PhWallet :size="20" aria-hidden="true"/>模拟货款</h2>
     <strong class="detail-price">{{money(order.amount)}}</strong>
     <div class="row spread"><span class="muted">资金状态</span><StatusBadge :value="order.fundsStatus"/></div>
     <p v-if="!support&&side==='buying'" class="muted">我的余额 {{money(session.balance||0)}}</p>
    </section>
    <section class="panel stack">
     <h2>售后记录</h2>
     <p v-if="!order.cases.length" class="muted">当前订单暂无售后记录。</p>
     <RouterLink v-for="item in order.cases" :key="item.id" :to="caseLink(item.id)" class="related-link row spread"><span>{{label(item.kind)}}详情 →</span><StatusBadge :value="item.status"/></RouterLink>
    </section>
   </aside>
  </div>
 </PageState>
 <ActionDialog v-model="dialog" :title="action?.title||''" :description="action?.description||''" :requires-reason="!!action?.kind" reason-label="申请理由" :busy="busy" :error="actionError" @confirm="submit"/>
 <el-dialog v-model="shipping" :title="action?.title" width="min(560px, calc(100% - 32px))" :close-on-click-modal="false" :show-close="!busy&&!goodsBusy&&!packagingBusy" :close-on-press-escape="!busy&&!goodsBusy&&!packagingBusy">
  <form class="stack" @submit.prevent="ship">
   <p>{{action?.description}}</p>
   <label class="field-label">物流单号 <span class="required">必填</span><input id="tracking-number" v-model="trackingNumber" v-bind="attributes('tracking-number')" required maxlength="100"/><span v-if="fields['tracking-number']" id="tracking-number-error" class="error">{{fields['tracking-number']}}</span></label>
   <UploadField v-model="goods" :purpose="returning?'RETURN_GOODS':'GOODS'" label="商品凭证" input-id="goods-proof" :field-error="goods.length?undefined:fields['goods-proof']" :max="Math.min(5,6-packaging.length)" @busy="goodsBusy=$event"/>
   <UploadField v-model="packaging" :purpose="returning?'RETURN_PACKAGING':'PACKAGING'" label="包装凭证" input-id="packaging-proof" :field-error="fields['packaging-proof']" :max="Math.min(5,6-goods.length)" @busy="packagingBusy=$event"/>
   <p class="muted">商品和包装各至少一张，合计最多六张。提交后凭证将存档。</p>
   <p v-if="actionError" class="error" role="alert">{{actionError}}</p>
   <div class="row end"><button class="button" type="button" :disabled="busy||goodsBusy||packagingBusy" @click="shipping=false">返回</button><button class="button primary" type="submit" :disabled="busy||goodsBusy||packagingBusy">{{busy?'正在提交…':'确认'+action?.title}}</button></div>
  </form>
 </el-dialog>
</template>

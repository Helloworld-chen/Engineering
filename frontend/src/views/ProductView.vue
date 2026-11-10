<script setup lang="ts">
import {computed,ref} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {PhArrowLeft,PhFlag,PhUser,PhClock} from '@phosphor-icons/vue';
import {api,date,money,type Product,type Order} from '../api/client';
import {useWorkLoad} from '../lib/workLoad';
import {isStaff,homeFor,returnTo} from '../lib/access';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import ProductPhoto from '../components/ProductPhoto.vue';
import StatusBadge from '../components/StatusBadge.vue';
import ActionDialog from '../components/ActionDialog.vue';
const route=useRoute(),router=useRouter(),session=useSession();
const back=computed(()=>returnTo(route.query.from,session.user?.role,homeFor(session.user?.role),session.user?.role==='ADMIN'?['/admin','/moderation','/reports']:session.user?.role==='SUPPORT'?['/support','/cases','/orders']:['/','/reports']));
const id=encodeURIComponent(String(route.params.id));
const {data:product,loading,error,reload}=useWorkLoad(()=>api<Product>('/products/'+id),true);
const {busy,error:actionError,success,run}=useAction();
const selected=ref(0),dialog=ref(false),report=ref(false);
const owner=computed(()=>session.user?.id===product.value?.sellerId);
const available=computed(()=>product.value?.auditStatus==='APPROVED'&&product.value.saleStatus==='AVAILABLE');
function open(isReport:boolean){
 if(!session.user){void router.push({path:'/login',query:{redirect:route.fullPath}});return}
 actionError.value='';report.value=isReport;dialog.value=true;
}
function confirm(reason:string){
 void run(async()=>{
  if(report.value){
   await api('/reports','POST',{productId:product.value!.id,reason});dialog.value=false;
  }else{
   const order=await api<Order>('/orders','POST',{productId:product.value!.id});await router.push('/orders/'+order.id);
  }
 },report.value?'举报已提交，可在个人中心查看进度。':'');
}
</script>
<template>
 <RouterLink class="back-link" :to="back"><PhArrowLeft :size="16" aria-hidden="true"/> {{isStaff(session.user?.role)?'返回工作页面':back.startsWith('/reports')?'返回举报记录':'返回商品集市'}}</RouterLink>
 <PageState :loading="loading" :error="error" @retry="reload">
  <article v-if="product" class="detail-grid">
   <section class="stack" aria-label="商品照片">
    <ProductPhoto :image="product.images[selected]" :title="product.title" :category="product.category"/>
    <div v-if="product.images.length>1" class="photo-strip">
     <button v-for="(image,index) in product.images" :key="image" class="photo-select" type="button" :aria-label="'查看商品照片 '+(index+1)" :aria-pressed="selected===index" @click="selected=index"><img :src="'/api/v1/files/'+image" alt=""/></button>
    </div>
   </section>
   <section class="panel stack">
    <div class="row"><span class="category">{{product.category}}</span><StatusBadge :value="product.saleStatus"/><StatusBadge v-if="product.auditStatus!=='APPROVED'" :value="product.auditStatus"/></div>
    <h1>{{product.title}}</h1><strong class="detail-price">{{money(product.price)}}</strong>
    <div class="row metadata"><span><PhUser :size="16"/> {{product.sellerName||'卖家'}}</span><span><PhClock :size="16"/> {{date(product.createdAt)}}</span></div>
    <div class="section-divider"><h2>商品说明</h2><p class="preserve">{{product.description}}</p></div>
    <div v-if="!owner&&(!session.user||session.user.role==='USER')" class="stack section-divider">
     <button class="button primary" :disabled="!available" @click="open(false)">{{available?'立即下单':'当前无法下单'}}</button>
     <p v-if="available" class="muted">下单后请在 2 分钟内完成模拟付款。</p>
     <button v-if="product.auditStatus==='APPROVED'" class="button subtle" @click="open(true)"><PhFlag :size="16"/> 举报商品</button>
    </div>
    <p v-if="owner" class="muted section-divider">这是你发布的商品，无法向自己下单。你可以购买其他同学发布的商品。</p>
    <RouterLink v-if="owner&&['AVAILABLE','OFF_SHELF'].includes(product.saleStatus)" class="button" :to="'/products/'+product.id+'/edit'">编辑商品</RouterLink>
    <p v-if="success" role="status" class="success">{{success}}</p>
   </section>
  </article>
 </PageState>
 <ActionDialog v-model="dialog" :title="report?'举报商品':'下单'" :description="report?'请说明商品涉嫌违规的情况。':'创建订单后，系统将为你保留商品 2 分钟。'" :requires-reason="report" reason-label="举报理由" :busy="busy" :error="actionError" @confirm="confirm"/>
</template>

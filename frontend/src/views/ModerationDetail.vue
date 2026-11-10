<script setup lang="ts">
import {computed,ref} from 'vue';
import {useRoute} from 'vue-router';
import {api,ApiError,date,money,type Product} from '../api/client';
import {useWorkLoad} from '../lib/workLoad';
import {useAction} from '../lib/action';
import {returnTo} from '../lib/access';
import ProductPhoto from '../components/ProductPhoto.vue';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
import ActionDialog from '../components/ActionDialog.vue';
const route=useRoute();
const back=computed(()=>returnTo(route.query.from,'ADMIN','/moderation',['/moderation','/admin']));
const {data,loading,error,reload}=useWorkLoad(()=>api<Product>('/products/'+encodeURIComponent(String(route.params.id))));
const {busy,error:actionError,success,run}=useAction();
const photo=ref(0);
const dialog=ref(false),decision=ref('APPROVED'),version=ref(0);
function open(value:string){if(!data.value)return;version.value=data.value.version;decision.value=value;actionError.value='';dialog.value=true}
function submit(reason:string){void run(async()=>{
 try{await api('/moderation/products/'+encodeURIComponent(String(route.params.id)),'POST',{decision:decision.value,reason,version:version.value})}
 catch(failure){
  if(failure instanceof ApiError&&failure.status===409){dialog.value=false;await reload();window.dispatchEvent(new Event('market:changed'));throw new Error('商品状态或内容已变化，已重新读取，请核对后再次审核。')}
  throw failure;
 }
 dialog.value=false;await reload();window.dispatchEvent(new Event('market:changed'));
},'审核结果已保存。')}
</script>
<template>
 <RouterLink class="back-link" :to="back">← 返回{{back.startsWith('/admin')?'工作台':'商品审核'}}</RouterLink>
 <div class="page-heading"><div><h1>审核商品</h1><p>请核对商品照片、说明和价格，再提交审核结论。</p></div></div>
 <p v-if="success" class="notice success" role="status">{{success}}</p><p v-if="actionError&&!dialog" class="notice error" role="alert">{{actionError}}</p>
 <PageState :loading="loading" :error="error" @retry="reload()"><article v-if="data" class="detail-grid">
  <section class="stack" aria-label="商品照片"><ProductPhoto :image="data.images[photo]||data.images[0]" :title="data.title" :category="data.category"/><div v-if="data.images.length>1" class="photo-strip"><button v-for="(image,index) in data.images" :key="image" class="photo-select" :aria-label="'查看商品照片 '+(index+1)" :aria-pressed="photo===index" @click="photo=index"><img :src="'/api/v1/files/'+image" alt=""/></button></div></section>
  <section class="panel stack"><div class="row"><span class="category">{{data.category}}</span><StatusBadge :value="data.auditStatus"/><StatusBadge :value="data.saleStatus"/></div><h2>{{data.title}}</h2><strong class="detail-price">{{money(data.price)}}</strong><p class="muted">发布者 {{data.sellerName||data.sellerId}} · {{date(data.createdAt)}}</p><p class="muted work-id">商品编号 {{data.id}}</p><div class="section-divider"><h3>商品说明</h3><p class="preserve">{{data.description}}</p></div></section>
 </article></PageState>
 <section class="panel stack moderation-decision" aria-label="审核操作">
  <h2>审核结论</h2><p v-if="error" class="error" role="alert">{{error}} <button class="button" @click="reload()">重试</button></p>
  <p v-else-if="data&&data.auditStatus!=='PENDING'" class="muted">此商品已完成审核。</p>
  <div v-else class="row"><button class="button" :disabled="loading||busy||!data" @click="open('REJECTED')">驳回</button><button class="button primary" :disabled="loading||busy||!data" @click="open('APPROVED')">通过</button></div>
 </section>
 <ActionDialog v-model="dialog" :title="decision==='APPROVED'?'通过审核':'驳回审核'" :description="'审核商品：'+(data?.title||'')" requires-reason reason-label="审核理由" :busy="busy" :error="actionError" @confirm="submit"/>
</template>

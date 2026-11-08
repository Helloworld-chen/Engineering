<script setup lang="ts">
import {ref} from 'vue';
import {api,date,money,type Product} from '../api/client';
import {useLoad} from '../lib/load';
import {useAction} from '../lib/action';
import PageState from '../components/PageState.vue';
import ProductPhoto from '../components/ProductPhoto.vue';
import ActionDialog from '../components/ActionDialog.vue';
const {data,loading,error,reload}=useLoad(()=>api<{items:Product[]}>('/moderation/products'));
const {busy,error:actionError,success,run}=useAction();
const dialog=ref(false),selected=ref<Product>(),decision=ref('APPROVED');
function open(product:Product,value:string){selected.value=product;decision.value=value;actionError.value='';dialog.value=true}
function submit(reason:string){
 const product=selected.value!;
 void run(async()=>{
  await api('/moderation/products/'+product.id,'POST',{decision:decision.value,reason,version:product.version});
  dialog.value=false;await reload();
 },'审核结果已保存。');
}
</script>
<template>
 <div class="page-heading"><div><h1>商品审核</h1><p>检查商品说明与照片，审核结果会通知卖家。</p></div></div>
 <p v-if="success" class="notice success" role="status">{{success}}</p>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!data?.items.length" class="panel empty"><h2>暂无待审核商品</h2><p>卖家提交商品后，将显示在这里。</p></div>
  <div v-else class="record-list">
   <article v-for="item in data.items" :key="item.id" class="record">
    <ProductPhoto class="record-photo" :image="item.images[0]" :title="item.title" :category="item.category"/>
    <div class="record-body stack tight"><RouterLink class="record-title" :to="'/products/'+item.id">{{item.title}}</RouterLink><p class="clamp">{{item.description}}</p><div class="row"><span class="category">{{item.category}}</span><strong>{{money(item.price)}}</strong><span class="muted">{{date(item.createdAt)}}</span></div></div>
    <div class="row record-actions"><button class="button" @click="open(item,'REJECTED')">驳回</button><button class="button primary" @click="open(item,'APPROVED')">通过</button></div>
   </article>
  </div>
 </PageState>
 <ActionDialog v-model="dialog" :title="decision==='APPROVED'?'通过审核':'驳回审核'" :description="'审核商品：'+(selected?.title||'')" requires-reason reason-label="审核理由" :busy="busy" :error="actionError" @confirm="submit"/>
</template>

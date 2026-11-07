<script setup lang="ts">
import {useRoute} from 'vue-router';
import {PhPlus} from '@phosphor-icons/vue';
import {api,money,type Product} from '../api/client';
import {useLoad} from '../lib/load';
import {useAction} from '../lib/action';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
import ProductPhoto from '../components/ProductPhoto.vue';
const route=useRoute();
const {data,loading,error,reload}=useLoad(()=>api<{items:Product[]}>('/my/products'));
const {busy,error:actionError,success,run}=useAction();
function change(product:Product){
 const off=product.saleStatus==='AVAILABLE';
 void run(async()=>{
  await api('/products/'+product.id,'PATCH',{saleStatus:off?'OFF_SHELF':'AVAILABLE',version:product.version});
  await reload();
 },off?'商品已下架。':'商品销售状态已更新。');
}
</script>
<template>
 <div class="page-heading"><div><h1>我的商品</h1><p>查看审核结果，维护商品信息和销售状态。</p></div><RouterLink class="button primary" to="/publish"><PhPlus :size="18"/>发布商品</RouterLink></div>
 <p v-if="actionError" class="error notice" role="alert">{{actionError}}</p>
 <p v-else-if="success||route.query.saved==='1'" class="success notice" role="status">{{success||'商品已提交审核。'}}</p>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!data?.items.length" class="panel empty"><h2>还没有发布商品</h2><p>提交商品照片、价格和说明后即可申请审核。</p><RouterLink class="button primary" to="/publish">发布第一件商品</RouterLink></div>
  <div v-else class="record-list">
   <article v-for="product in data.items" :key="product.id" class="record">
    <ProductPhoto class="record-photo" :image="product.images[0]" :title="product.title" :category="product.category"/>
    <div class="record-body stack tight">
     <RouterLink class="record-title" :to="'/products/'+product.id">{{product.title}}</RouterLink>
     <div class="row"><strong>{{money(product.price)}}</strong><span class="category">{{product.category}}</span><StatusBadge :value="product.auditStatus"/><StatusBadge :value="product.saleStatus"/></div>
    </div>
    <div v-if="['AVAILABLE','OFF_SHELF'].includes(product.saleStatus)" class="row record-actions">
     <RouterLink class="button" :to="'/products/'+product.id+'/edit'">编辑</RouterLink>
     <button class="button" :disabled="busy" @click="change(product)">{{product.saleStatus==='AVAILABLE'?'下架':'上架'}}</button>
    </div>
   </article>
  </div>
 </PageState>
</template>

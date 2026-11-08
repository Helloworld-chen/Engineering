<script setup lang="ts">
import {useRoute} from 'vue-router';
import {computed} from 'vue';
import {PhArrowRight} from '@phosphor-icons/vue';
import {api,date,money,type Order} from '../api/client';
import {useLoad} from '../lib/load';
import PageState from '../components/PageState.vue';
import ProductPhoto from '../components/ProductPhoto.vue';
import StatusBadge from '../components/StatusBadge.vue';
const route=useRoute();
const side=computed(()=>route.query.side==='selling'?'selling':'buying');
const {data,loading,error,reload}=useLoad(()=>api<{items:Order[]}>('/orders?side='+side.value));
</script>
<template>
 <div class="page-heading"><div><h1>我的订单</h1><p>查看付款、物流、售后及模拟货款进度。</p></div></div>
 <nav class="tabs list-tabs" aria-label="订单交易方向"><RouterLink to="/orders" :class="{selected:side==='buying'}" :aria-current="side==='buying'?'page':undefined">我买到的</RouterLink><RouterLink to="/orders?side=selling" :class="{selected:side==='selling'}" :aria-current="side==='selling'?'page':undefined">我卖出的</RouterLink></nav>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!data?.items.length" class="panel empty"><h2>{{side==='buying'?'还没有购买订单':'还没有出售订单'}}</h2><p>{{side==='buying'?'下单后，可在这里付款或查看履约进度。':'买家下单后，可在这里查看订单。'}}</p><RouterLink class="button" :to="side==='buying'?'/':'/my/products'">{{side==='buying'?'浏览商品':'查看我的商品'}}</RouterLink></div>
  <div v-else class="record-list">
   <RouterLink v-for="order in data.items" :key="order.id" class="record record-link" :to="'/orders/'+order.id">
    <ProductPhoto class="record-photo" :image="order.product.images[0]" :title="order.product.title" :category="order.product.category"/>
    <div class="record-body stack tight"><h2 class="record-title">{{order.product.title}}</h2><div class="row"><StatusBadge :value="order.status"/><StatusBadge :value="order.fundsStatus"/><span class="muted">{{date(order.createdAt)}}</span></div></div>
    <div class="row record-actions"><strong>{{money(order.amount)}}</strong><PhArrowRight :size="18" aria-hidden="true"/></div>
   </RouterLink>
  </div>
 </PageState>
</template>

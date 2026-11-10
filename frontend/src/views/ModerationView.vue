<script setup lang="ts">
import {computed} from 'vue';
import {api,date,money,type Product} from '../api/client';
import {useWorkLoad} from '../lib/workLoad';
import {ordered,pageItems,useListQuery} from '../lib/worklist';
import PageState from '../components/PageState.vue';
import ProductPhoto from '../components/ProductPhoto.vue';
import WorkPagination from '../components/WorkPagination.vue';
const {data,loading,error,reload}=useWorkLoad(()=>api<{items:Product[]}>('/moderation/products'));
const {query,set,route}=useListQuery(),q=query('q'),page=query('page','1');
const result=computed(()=>pageItems(ordered((data.value?.items||[]).filter(p=>p.title.toLocaleLowerCase().includes(q.value.trim().toLocaleLowerCase())),true),page.value));
</script>
<template>
 <div class="page-heading"><div><h1>商品审核</h1><p>核对照片和商品说明，较早提交的商品优先展示。审核结果会通知卖家。</p></div><button class="button" @click="reload()">刷新列表</button></div>
 <div class="work-toolbar"><label class="field-label">搜索商品标题<input :value="q" type="search" @input="set({q:($event.target as HTMLInputElement).value})"/></label></div>
 <PageState :loading="loading" :error="error" @retry="reload()">
  <div v-if="!result.total" class="panel empty"><h2>{{q?'没有匹配的待审核商品':'暂无待审核商品'}}</h2><p>{{q?'请调整搜索关键词。':'卖家提交商品后，将显示在这里。'}}</p></div>
  <div v-else class="record-list"><article v-for="item in result.items" :key="item.id" class="record">
   <ProductPhoto class="record-photo" :image="item.images[0]" :title="item.title" :category="item.category"/>
   <div class="record-body stack tight"><RouterLink class="record-title" :to="{path:'/moderation/'+item.id,query:{from:route.fullPath}}">{{item.title}}</RouterLink><p class="clamp">{{item.description}}</p><div class="row"><span class="category">{{item.category}}</span><strong>{{money(item.price)}}</strong><span class="muted">{{date(item.createdAt)}}</span></div></div>
   <RouterLink class="button primary record-actions" :to="{path:'/moderation/'+item.id,query:{from:route.fullPath}}">审核商品</RouterLink>
  </article></div>
  <WorkPagination v-bind="result" @change="set({page:String($event)})"/>
 </PageState>
</template>

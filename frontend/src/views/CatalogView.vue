<script setup lang="ts">
import {ref,watch} from 'vue';
import {PhMagnifyingGlass,PhPlus,PhArrowUpRight,PhSquaresFour} from '@phosphor-icons/vue';
import {api,money,type Product} from '../api/client';
import {useLoad} from '../lib/load';
import {useSession} from '../stores/session';
import ProductPhoto from '../components/ProductPhoto.vue';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
const session=useSession();
const input=ref(''), keyword=ref(''), category=ref(''), page=ref(1);
const categories=['','数码','图书','生活','运动','其他'];
const size=12;
const {data,loading,error,reload}=useLoad(()=>api<{items:Product[],total:number}>('/products?'+new URLSearchParams({keyword:keyword.value,category:category.value,page:String(page.value),pageSize:String(size)})));
watch(()=>session.user?.id,()=>{page.value=1;data.value=undefined;void reload()});
function search(){keyword.value=input.value.trim();page.value=1;void reload()}
function filter(value:string){category.value=value;page.value=1;void reload()}
function paginate(value:number){page.value=value;void reload()}
</script>
<template>
 <section class="catalog-heading">
  <div class="eyebrow"><PhSquaresFour :size="16" aria-hidden="true"/> 商品集市</div>
  <div class="page-heading">
   <div><h1>发现闲置，让物品继续有用</h1><p>{{session.user?'浏览其他同学发布的商品，找到合适的即可直接下单。':'浏览校园闲置，找到合适的商品即可直接下单。'}}</p></div>
   <RouterLink v-if="session.user?.role==='USER'||!session.user" to="/publish" class="button primary"><PhPlus :size="18" aria-hidden="true"/>发布商品</RouterLink>
  </div>
 </section>
 <section aria-label="商品目录">
  <p v-if="session.user?.role==='USER'" class="muted">这里展示其他同学的商品。你发布的商品可在 <RouterLink to="/my/products">“我的商品”</RouterLink> 中查看和管理。</p>
  <form class="search-form" @submit.prevent="search">
   <PhMagnifyingGlass :size="20" aria-hidden="true"/>
   <input v-model="input" aria-label="商品关键词" placeholder="搜索商品名称" maxlength="120"/>
   <button class="button primary" type="submit">搜索</button>
  </form>
  <div class="filter-row">
   <div class="tabs" role="group" aria-label="按商品分类筛选">
    <button v-for="item in categories" :key="item" type="button" :aria-pressed="category===item" @click="filter(item)">{{item||'全部商品'}}</button>
   </div>
   <span class="muted" v-if="data" aria-live="polite">共 {{data.total}} 件商品</span>
  </div>
  <PageState :loading="loading" :error="error" @retry="reload">
   <div v-if="!data?.items.length" class="panel empty"><h2>没有匹配的商品</h2><p>试试其他商品名称或分类。</p><button class="button" @click="input='';keyword='';filter('')">清除筛选</button></div>
   <div v-else class="catalog-grid">
    <article v-for="product in data.items" :key="product.id" class="product-card">
     <RouterLink :to="'/products/'+product.id" class="product-link">
      <ProductPhoto :image="product.images[0]" :title="product.title" :category="product.category"/>
      <div class="product-card-body">
       <div class="row spread"><span class="category">{{product.category}}</span><StatusBadge :value="product.saleStatus"/></div>
       <h2>{{product.title}}</h2>
       <div class="row spread"><strong class="price">{{money(product.price)}}</strong><PhArrowUpRight :size="18" aria-hidden="true"/></div>
      </div>
     </RouterLink>
    </article>
   </div>
   <el-pagination v-if="data && data.total>size" class="pagination" layout="prev, pager, next" :total="data.total" :page-size="size" :current-page="page" :pager-count="5" @current-change="paginate"/>
  </PageState>
 </section>
</template>

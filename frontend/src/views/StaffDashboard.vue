<script setup lang="ts">
import {computed} from 'vue';
import {api,date,type Product,type Report,type Case} from '../api/client';
import {useSession} from '../stores/session';
import {useWorkLoad} from '../lib/workLoad';
import {ordered,caseGroup} from '../lib/worklist';
import {label} from '../lib/labels';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
const admin=useSession().user?.role==='ADMIN';
const products=useWorkLoad(async()=>admin?(await api<{items:Product[]}>('/moderation/products')).items:[]);
const reports=useWorkLoad(async()=>admin?(await api<{items:Report[]}>('/reports')).items:[]);
const cases=useWorkLoad(async()=>!admin?(await api<{items:Case[]}>('/cases')).items:[]);
const pendingProducts=computed(()=>ordered(products.data.value||[],true));
const pendingReports=computed(()=>ordered((reports.data.value||[]).filter(r=>r.status==='PENDING'),true));
const pendingCases=computed(()=>ordered((cases.data.value||[]).filter(c=>caseGroup(c.status)==='pending'),true));
const stats=computed(()=>admin?[
 {title:'待审核商品',value:products.data.value?.length,to:'/moderation',error:products.error.value},
 {title:'待处理举报',value:reports.data.value?pendingReports.value.length:undefined,to:'/reports?status=PENDING',error:reports.error.value}
]:['pending','active','closed'].map((group,i)=>({title:['待客服处理','其他处理中','已关闭'][i],value:cases.data.value?.filter(c=>caseGroup(c.status)===group).length,to:'/cases?group='+group,error:cases.error.value})));
function refresh(){void products.reload();void reports.reload();void cases.reload()}
</script>
<template>
 <div class="page-heading"><div><h1>{{admin?'管理员工作台':'客服工作台'}}</h1><p>{{admin?'审核待发布商品，跟进商品举报。':'核对售后材料，处理退款与交易争议。'}}按提交时间优先查看较早的待办。</p></div><button class="button" @click="refresh">刷新待办</button></div>
 <div class="work-stats"><RouterLink v-for="stat in stats" :key="stat.title" :to="stat.to" class="panel work-stat"><span>{{stat.title}}</span><strong>{{stat.value??'—'}}</strong><span v-if="stat.error" class="error">同步失败，重试见下方</span></RouterLink></div>
 <div v-if="admin" class="work-columns">
  <section class="panel stack" aria-label="待审核商品"><div class="row spread"><h2>待审核商品</h2><RouterLink to="/moderation">查看全部</RouterLink></div>
   <PageState :loading="products.loading.value" :error="products.error.value" @retry="products.reload()"><p v-if="!pendingProducts.length" class="muted">暂无待审核商品。</p><article v-for="p in pendingProducts.slice(0,5)" :key="p.id" class="work-queue"><RouterLink class="record-title" :to="{path:'/moderation/'+p.id,query:{from:'/admin'}}">{{p.title}}</RouterLink><p class="muted">提交于 {{date(p.createdAt)}}</p><RouterLink class="button" :to="{path:'/moderation/'+p.id,query:{from:'/admin'}}">审核商品</RouterLink></article></PageState>
  </section>
  <section class="panel stack" aria-label="待处理举报"><div class="row spread"><h2>待处理举报</h2><RouterLink to="/reports?status=PENDING">查看全部</RouterLink></div>
   <PageState :loading="reports.loading.value" :error="reports.error.value" @retry="reports.reload()"><p v-if="!pendingReports.length" class="muted">暂无待处理举报。</p><article v-for="r in pendingReports.slice(0,5)" :key="r.id" class="work-queue"><p class="clamp">{{r.reason}}</p><p class="muted work-id">商品 {{r.productId}} · {{date(r.createdAt)}}</p><RouterLink class="button" :to="{path:'/reports',query:{status:'PENDING',q:r.id}}">处理举报</RouterLink></article></PageState>
  </section>
 </div>
 <section v-else class="panel stack" aria-label="待客服处理"><div class="row spread"><h2>待客服处理</h2><RouterLink to="/cases?group=pending">查看全部</RouterLink></div>
  <PageState :loading="cases.loading.value" :error="cases.error.value" @retry="cases.reload()"><p v-if="!pendingCases.length" class="muted">暂无待客服处理的售后。</p><article v-for="c in pendingCases.slice(0,5)" :key="c.id" class="work-queue"><div class="row spread"><h3>{{label(c.kind)}}</h3><StatusBadge :value="c.status"/></div><p class="clamp">{{c.reason}}</p><p class="muted work-id">售后单 {{c.id}} · {{date(c.createdAt)}}</p><RouterLink class="button" :to="{path:'/cases/'+c.id,query:{from:'/support'}}">处理售后</RouterLink></article></PageState>
 </section>
</template>

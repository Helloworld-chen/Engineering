<script setup lang="ts">
import {computed,ref} from 'vue';
import {api,date,type Report} from '../api/client';
import {useWorkLoad} from '../lib/workLoad';
import {ordered,pageItems,useListQuery} from '../lib/worklist';
import WorkPagination from '../components/WorkPagination.vue';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
import ActionDialog from '../components/ActionDialog.vue';
const session=useSession();
const {data,loading,error,reload}=useWorkLoad(()=>api<{items:Report[]}>('/reports'));
const {busy,error:actionError,success,run}=useAction();
const selected=ref(''),dialog=ref(false);
const {query,set,route}=useListQuery(),q=query('q'),status=query('status',session.user?.role==='ADMIN'?'PENDING':'ALL'),page=query('page','1');
const result=computed(()=>pageItems(ordered((data.value?.items||[]).filter(r=>(!['PENDING','RESOLVED'].includes(status.value)||r.status===status.value)&&(!q.value||[r.id,r.productId,r.reason].some(v=>v.includes(q.value.trim())))),status.value==='PENDING'),page.value));
function submit(result:string){
 void run(async()=>{
  await api('/reports/'+selected.value+'/resolve','POST',{result});
  dialog.value=false;await reload();window.dispatchEvent(new Event('market:changed'));
 },'举报处理结果已保存。');
}
</script>
<template>
 <div class="page-heading"><div><h1>{{session.user?.role==='ADMIN'?'举报处理':'我的举报'}}</h1><p>查看商品举报理由和处理结果。</p></div><button class="button" @click="reload()">刷新列表</button></div>
 <div class="work-toolbar"><label class="field-label">举报状态<select :value="status" @change="set({status:($event.target as HTMLSelectElement).value})"><option value="PENDING">待处理</option><option value="RESOLVED">已处理</option><option value="ALL">全部</option></select></label><label class="field-label">搜索举报<input type="search" :value="q" placeholder="举报编号、商品编号或理由" @input="set({q:($event.target as HTMLInputElement).value})"/></label></div>
 <p v-if="success" class="notice success" role="status">{{success}}</p>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!result.total" class="panel empty"><h2>暂无符合条件的举报</h2><p>举报提交后会显示在这里。</p></div>
  <div v-else class="record-list">
   <article v-for="item in result.items" :key="item.id" class="record">
    <div class="record-body stack tight"><RouterLink class="record-title" :to="{path:'/products/'+item.productId,query:{from:route.fullPath}}">查看被举报商品 →</RouterLink><p class="muted work-id">商品编号 {{item.productId}}</p><p class="preserve">{{item.reason}}</p><p v-if="item.result" class="preserve">处理结果 · {{item.result}}</p><p class="muted">{{date(item.createdAt)}}</p></div>
    <div class="row record-actions"><StatusBadge :value="item.status"/><button v-if="session.user?.role==='ADMIN'&&item.status==='PENDING'" class="button primary" @click="selected=item.id;actionError='';dialog=true">处理举报</button></div>
   </article>
  </div>
  <WorkPagination v-bind="result" @change="set({page:String($event)})"/>
 </PageState>
 <ActionDialog v-model="dialog" title="保存处理结果" description="提交结果后，系统会通知举报人。" requires-reason reason-label="处理结果" :busy="busy" :error="actionError" @confirm="submit"/>
</template>

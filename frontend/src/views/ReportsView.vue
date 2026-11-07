<script setup lang="ts">
import {ref} from 'vue';
import {api,date,type Report} from '../api/client';
import {useLoad} from '../lib/load';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
import PageState from '../components/PageState.vue';
import StatusBadge from '../components/StatusBadge.vue';
import ActionDialog from '../components/ActionDialog.vue';
const session=useSession();
const {data,loading,error,reload}=useLoad(()=>api<{items:Report[]}>('/reports'));
const {busy,error:actionError,success,run}=useAction();
const selected=ref(''),dialog=ref(false);
function submit(result:string){
 void run(async()=>{
  await api('/reports/'+selected.value+'/resolve','POST',{result});
  dialog.value=false;await reload();
 },'举报处理结果已保存。');
}
</script>
<template>
 <div class="page-heading"><div><h1>{{session.user?.role==='ADMIN'?'举报处理':'我的举报'}}</h1><p>查看商品举报理由和处理结果。</p></div></div>
 <p v-if="success" class="notice success" role="status">{{success}}</p>
 <PageState :loading="loading" :error="error" @retry="reload">
  <div v-if="!data?.items.length" class="panel empty"><h2>暂无商品举报</h2><p>举报提交后会显示在这里。</p></div>
  <div v-else class="record-list">
   <article v-for="item in data.items" :key="item.id" class="record">
    <div class="record-body stack tight"><RouterLink class="record-title" :to="'/products/'+item.productId">查看被举报商品 →</RouterLink><p class="preserve">{{item.reason}}</p><p v-if="item.result" class="preserve">处理结果 · {{item.result}}</p><p class="muted">{{date(item.createdAt)}}</p></div>
    <div class="row record-actions"><StatusBadge :value="item.status"/><button v-if="session.user?.role==='ADMIN'&&item.status==='PENDING'" class="button primary" @click="selected=item.id;actionError='';dialog=true">处理举报</button></div>
   </article>
  </div>
 </PageState>
 <ActionDialog v-model="dialog" title="保存处理结果" description="提交结果后，系统会通知举报人。" requires-reason reason-label="处理结果" :busy="busy" :error="actionError" @confirm="submit"/>
</template>

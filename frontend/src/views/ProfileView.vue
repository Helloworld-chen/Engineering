<script setup lang="ts">
import {ref,watch,reactive} from 'vue';
import {api,date,money,type UserProfile} from '../api/client';
import {label} from '../lib/labels';
import {useAction} from '../lib/action';
import {useSession} from '../stores/session';
import {useWorkLoad} from '../lib/workLoad';
import PageState from '../components/PageState.vue';
import UploadField from '../components/UploadField.vue';
const session=useSession();
const displayName=ref(session.user?.displayName||''),seconds=ref('11'),confirmation=ref(''),resetDialog=ref(false),serverTime=ref('');
const demo=useAction();
const form=reactive({school:'',campus:'',bio:'',gender:''}),avatar=ref<string[]>([]),avatarBusy=ref(false);
const saving=ref(false),saveError=ref(''),saved=ref(''),dirty=ref(false);let applying=false,epoch=0;
const {data:details,loading:profileLoading,error:profileError,reload:reloadProfile}=useWorkLoad(()=>api<UserProfile>('/profile'));
function apply(value:UserProfile){applying=true;displayName.value=value.displayName;avatar.value=value.avatarId?[value.avatarId]:[];Object.assign(form,{school:value.school,campus:value.campus,bio:value.bio,gender:value.gender});applying=false;dirty.value=false}
watch([displayName,avatar,form],()=>{if(!applying)dirty.value=true},{deep:true,flush:'sync'});
watch(details,value=>{if(value&&!dirty.value)apply(value)});

watch(()=>session.user?.id,()=>{epoch++;applying=true;displayName.value=session.user?.displayName||'';Object.assign(form,{school:'',campus:'',bio:'',gender:''});avatar.value=[];avatarBusy.value=false;applying=false;dirty.value=false;saving.value=false;saveError.value='';saved.value='';serverTime.value='';resetDialog.value=false},{flush:'sync'});
async function save(){
 const generation=epoch;saving.value=true;saveError.value='';saved.value='';
 try{
  if(!displayName.value.trim())throw new Error('请填写昵称');
  for(const [label,value,max] of [['昵称',displayName.value,80],['学校',form.school,80],['校区',form.campus,80],['个人简介',form.bio,300]] as const)if(Array.from(value.trim()).length>max)throw new Error(label+'最多'+max+'字');
  const result=await api<{profile:UserProfile}>('/profile','PATCH',{displayName:displayName.value.trim(),avatarId:avatar.value[0]||null,...(session.user?.role==='USER'?form:{})});
  if(generation!==epoch)return;apply(result.profile);await session.refresh();if(generation!==epoch)return;saved.value='资料已更新。';window.dispatchEvent(new Event('market:changed'));
 }catch(failure){if(generation===epoch)saveError.value=failure instanceof Error?failure.message:'资料保存失败'}finally{if(generation===epoch)saving.value=false}
}
function advance(){
 void demo.run(async()=>{
  const value=Number(seconds.value);
  if(!Number.isInteger(value)||value<=0||value>31536000)throw new Error('请输入 1 至 31536000 的整数秒数');
  const result=await api<{serverTime:string,ready:boolean}>('/demo/advance','POST',{millis:value*1000});
  serverTime.value=result.serverTime;
  if(!result.ready)throw new Error('演示时钟已推进，后端正在处理到期任务。');
  window.dispatchEvent(new Event('market:changed'));
 },'演示时钟已推进。');
}
function reset(){
 const generation=epoch;
 void demo.run(async()=>{
  if(confirmation.value!=='RESET')throw new Error('请输入 RESET 确认重置');
  await api('/demo/reset','POST',{confirmation:confirmation.value});if(generation!==epoch)return;resetDialog.value=false;serverTime.value='';await session.refresh();if(generation!==epoch)return;
  const restored=await api<UserProfile>('/profile');if(generation!==epoch)return;apply(restored);avatarBusy.value=false;window.dispatchEvent(new Event('market:changed'));
 },'演示数据已重置。');
}
</script>
<template>
 <div class="page-heading"><div><h1>{{session.user?.role==='USER'?'个人中心':'账号资料'}}</h1><p>{{session.user?.role==='USER'?'维护基本资料，查看账号身份和模拟余额。':'查看岗位身份和维护账号资料。'}}</p></div></div>
 <div class="order-grid">
  <section class="panel stack">
   <h2>账号资料</h2>
   <dl class="facts"><dt>用户名</dt><dd>{{session.user?.username}}</dd><dt>身份</dt><dd>{{label(session.user?.role||'')}}</dd><template v-if="session.user?.role==='USER'"><dt>模拟余额</dt><dd><strong>{{money(session.balance||0)}}</strong></dd></template></dl>
   <PageState :loading="profileLoading" :error="profileError" @retry="reloadProfile"><form class="stack section-divider" @submit.prevent="save">
    <fieldset class="stack profile-fields" :disabled="saving"><UploadField v-model="avatar" purpose="AVATAR" label="头像" :max="1" :required="false" @busy="avatarBusy=$event"/>
    <label class="field-label">昵称<input v-model="displayName" required maxlength="160"/></label>
    <template v-if="session.user?.role==='USER'"><label class="field-label">学校<input v-model="form.school" maxlength="160"/></label><label class="field-label">校区<input v-model="form.campus" maxlength="160"/></label><label class="field-label">性别<select v-model="form.gender"><option value="">不填写</option><option value="MALE">男</option><option value="FEMALE">女</option><option value="OTHER">其他</option></select></label><label class="field-label">个人简介<textarea v-model="form.bio" rows="4" maxlength="600"/></label><p class="muted">保存的资料可供交易相关用户查看，学校和校区为本人填写。</p></template>
    </fieldset><p v-if="saveError" class="error" role="alert">{{saveError}}</p><p v-if="saved" class="success" role="status">{{saved}}</p>
    <div><button class="button primary" :disabled="saving||avatarBusy" type="submit">{{saving?'正在保存…':'保存资料'}}</button></div>
   </form></PageState>
  </section>
  <aside class="panel stack"><h2>账号记录</h2><RouterLink v-if="session.user?.role==='USER'" class="related-link" :to="{path:'/users/'+session.user.id,query:{from:'/profile'}}">查看我的公开资料与信用 →</RouterLink><RouterLink class="related-link" to="/notifications">查看通知 →</RouterLink><RouterLink v-if="session.user?.role!=='SUPPORT'" class="related-link" to="/reports">{{session.user?.role==='ADMIN'?'举报处理':'我的举报'}} →</RouterLink></aside>
 </div>
 <section v-if="session.demo&&session.user?.role==='ADMIN'" class="panel stack demo-controls">
  <div class="eyebrow">仅课程演示</div><h2>演示控制</h2><p class="muted">推进后端业务时钟会处理到期订单、物流和售后任务。</p>
  <form class="row control-row" @submit.prevent="advance"><label class="field-label">推进时间（秒）<input v-model="seconds" type="number" min="1" max="31536000" required/></label><button class="button primary" type="submit" :disabled="demo.busy.value">推进演示时钟</button></form>
  <p v-if="serverTime" class="muted">当前演示时间 · {{date(serverTime)}}</p>
  <p v-if="demo.error.value&&!resetDialog" class="error" role="alert">{{demo.error.value}}</p>
  <p v-if="demo.success.value" class="success" role="status">{{demo.success.value}}</p>
  <div class="section-divider"><button class="button danger" :disabled="demo.busy.value" @click="confirmation='';demo.error.value='';resetDialog=true">重置演示数据</button></div>
 </section>
 <el-dialog v-model="resetDialog" title="重置演示数据" width="min(480px, calc(100% - 32px))" :close-on-click-modal="false" :show-close="!demo.busy.value" :close-on-press-escape="!demo.busy.value">
  <form class="stack" @submit.prevent="reset">
   <p>此操作会清除演示期间新增的商品、订单、售后、评价、扩展资料与通知，并恢复种子数据。</p>
   <label class="field-label">输入 RESET 确认<input v-model="confirmation" required autocomplete="off"/></label>
   <p v-if="demo.error.value" class="error" role="alert">{{demo.error.value}}</p>
   <div class="row end"><button type="button" class="button" :disabled="demo.busy.value" @click="resetDialog=false">返回</button><button class="button danger" type="submit" :disabled="demo.busy.value||confirmation!=='RESET'">确认重置</button></div>
  </form>
 </el-dialog>
</template>

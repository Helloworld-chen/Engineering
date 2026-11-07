<script setup lang="ts">
import {computed,ref} from 'vue';
import {useRoute,useRouter} from 'vue-router';
import {PhUserCircle} from '@phosphor-icons/vue';
import {api} from '../api/client';
import {useSession} from '../stores/session';
import {useAction} from '../lib/action';
import {useFieldErrors} from '../lib/fieldErrors';
const session=useSession(),route=useRoute(),router=useRouter();
const register=computed(()=>route.path==='/register');
const username=ref(''),password=ref(''),displayName=ref('');
const {busy,error,run}=useAction();
const {fields,clear,flag,attributes}=useFieldErrors();
const accounts=[['buyer','陈同学'],['seller','林同学'],['other','周同学'],['admin','管理员'],['support','客服']];
function submit(){
 clear();
 if(!username.value.trim())return flag('username','请填写用户名');
 if(register.value&&!/^[a-zA-Z0-9_]{3,32}$/.test(username.value.trim()))return flag('username','用户名需 3 至 32 位字母、数字或下划线');
 if(register.value&&!displayName.value.trim())return flag('display-name','请填写昵称');
 if(!password.value||register.value&&password.value.length<8)return flag('password',register.value?'密码至少 8 个字符':'请填写密码');
 void run(async()=>{
  if(!session.ready)await session.refresh();
  await api(register.value?'/auth/register':'/auth/login','POST',{username:username.value.trim(),password:password.value,...(register.value?{displayName:displayName.value.trim()}:{})});
  await session.refresh();
  const redirect=route.query.redirect;
  const safe=typeof redirect==='string'&&/^\/(?![/\\])[^\\]*$/.test(redirect);
  await router.replace(safe?redirect:'/');
 });
}
</script>
<template>
 <div class="auth-layout">
  <section class="panel auth-panel">
   <div class="eyebrow"><PhUserCircle :size="18" aria-hidden="true"/> 校园集市</div>
   <h1>{{register?'注册账号':'登录校园集市'}}</h1>
   <p class="muted">{{register?'创建普通用户账号后即可发布和购买商品。':'登录后查看订单、消息与售后进度。'}}</p>
   <el-form class="stack form-stack" label-position="top" @submit.prevent="submit">
    <el-form-item label="用户名" for="username" required><el-input id="username" v-model="username" v-bind="attributes('username')" autocomplete="username" :maxlength="32" required/><p v-if="fields.username" id="username-error" class="error">{{fields.username}}</p></el-form-item>
    <el-form-item v-if="register" label="昵称" for="display-name" required><el-input id="display-name" v-model="displayName" v-bind="attributes('display-name')" maxlength="80" required/><p v-if="fields['display-name']" id="display-name-error" class="error">{{fields['display-name']}}</p></el-form-item>
    <el-form-item label="密码" for="password" required><el-input id="password" v-model="password" v-bind="attributes('password')" type="password" :autocomplete="register?'new-password':'current-password'" maxlength="64" required/><p v-if="fields.password" id="password-error" class="error">{{fields.password}}</p></el-form-item>
    <p v-if="error" class="error" role="alert">{{error}}</p>
    <button class="button primary" :disabled="busy" type="submit">{{busy?'正在提交…':register?'注册':'登录'}}</button>
   </el-form>
   <div class="row auth-switch"><span>{{register?'已有账号？':'还没有账号？'}}</span><RouterLink :to="{path:register?'/login':'/register',query:route.query}">{{register?'去登录':'注册账号'}}</RouterLink></div>
  </section>
  <aside v-if="session.demo&&!register" class="panel demo-accounts">
   <div class="eyebrow">课程演示</div><h2>演示账号</h2>
   <p>选择一个账号以填入用户名和密码。普通用户均可发布和购买商品。</p>
   <button v-for="[name,role] in accounts" :key="name" type="button" class="button" @click="username=name;password='Campus123!'"><span>{{role}}</span><code>{{name}}</code></button>
  </aside>
 </div>
</template>

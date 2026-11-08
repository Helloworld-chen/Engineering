import {defineStore} from 'pinia';
import {ref} from 'vue';
import {api,setCsrf,setSessionRecovery,type Session,type Actor} from '../api/client';

export const useSession=defineStore('session',()=>{
 const user=ref<Actor|null>(null),balance=ref<number|null>(null),demo=ref(false),ready=ref(false);
 let generation=0;
 let verification:Promise<void>|undefined;
 async function refresh(){
  const request=++generation;
  const data=await api<Session>('/session');
  if(request!==generation)return;
  user.value=data.user;
  balance.value=data.balance;
  demo.value=data.demo;
  setCsrf(data.csrfToken);
  ready.value=true;
 }
 function verify(){
  verification??=refresh().finally(()=>{verification=undefined});
  return verification;
 }
 setSessionRecovery(verify);

 async function logout(){
  let failure:unknown;
  try{
   await api('/auth/logout','POST',{});
  }catch(error){
   failure=error;
  }finally{
   try{await refresh()}catch(error){
    generation++;
    user.value=null;
    balance.value=null;
    ready.value=false;
    setCsrf('');
    throw failure||error;
   }
  }
  if(failure&&user.value)throw failure;
 }
 return {user,balance,demo,ready,refresh,verify,logout};
});

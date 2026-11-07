import {ref,shallowRef,onMounted,onUnmounted} from 'vue';

export function useLoad<T>(loader:()=>Promise<T>){
 const data=shallowRef<T>(),loading=ref(true),error=ref('');
 let generation=0;
 async function reload(background=false){
  const current=++generation;
  if(!background||data.value===undefined)loading.value=true;
  error.value='';
  try{
   const result=await loader();
   if(current===generation)data.value=result;
  }catch(failure){
   if(current===generation)error.value=failure instanceof Error?failure.message:'读取失败';
  }finally{
   if(current===generation)loading.value=false;
  }
 }
 const refresh=()=>void reload(true);
 onMounted(()=>{
  void reload();
  window.addEventListener('market:changed',refresh);
 });
 onUnmounted(()=>{
  generation++;
  window.removeEventListener('market:changed',refresh);
 });
 return {data,loading,error,reload};
}

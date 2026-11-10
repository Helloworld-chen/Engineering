import {ref,shallowRef,watch,onMounted,onUnmounted} from 'vue';
import {useSession} from '../stores/session';
import {isStaff} from './access';

/** Coalesce refreshes, discard responses from previous accounts and poll only visible staff pages. */
export function useWorkLoad<T>(loader:()=>Promise<T>,publicPage=false){
 const session=useSession(),data=shallowRef<T>(),loading=ref(true),error=ref('');
 let epoch=0,alive=true,timer:ReturnType<typeof setInterval>|undefined;
 let task:{epoch:number,pending:boolean,promise:Promise<void>}|undefined;
 function reload(_background=false):Promise<void>{
  if(!alive||(!publicPage&&!session.user))return Promise.resolve();
  if(task){task.pending=true;return task.promise}
  const current={epoch,pending:true,promise:Promise.resolve()};task=current;
  current.promise=(async()=>{
   while(current.pending&&current.epoch===epoch&&alive){
    current.pending=false;loading.value=data.value===undefined;
    try{const result=await loader();if(current.epoch===epoch&&alive){data.value=result;error.value=''}}
    catch(failure){if(current.epoch===epoch&&alive)error.value=failure instanceof Error?failure.message:'读取失败，请重试'}
   }
  })().finally(()=>{if(task===current){task=undefined;loading.value=false;if(current.pending&&alive)return reload()}});
  return current.promise;
 }
 const refresh=()=>{if(document.visibilityState!=='hidden')void reload(true)};
 function schedule(){
  clearInterval(timer);timer=undefined;
  if(isStaff(session.user?.role)&&document.visibilityState!=='hidden')timer=setInterval(refresh,30000);
 }
 const visible=()=>{schedule();refresh()};
 watch(()=>session.user?.id,()=>{epoch++;task=undefined;data.value=undefined;error.value='';loading.value=!!session.user;schedule();void reload()},{flush:'sync'});
 onMounted(()=>{schedule();void reload();window.addEventListener('market:changed',refresh);window.addEventListener('focus',refresh);window.addEventListener('online',refresh);document.addEventListener('visibilitychange',visible)});
 onUnmounted(()=>{alive=false;epoch++;clearInterval(timer);window.removeEventListener('market:changed',refresh);window.removeEventListener('focus',refresh);window.removeEventListener('online',refresh);document.removeEventListener('visibilitychange',visible)});
 return {data,loading,error,reload};
}

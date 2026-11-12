import {nextTick,onMounted,onBeforeUnmount,watch,type Ref} from 'vue';
import {useRoute} from 'vue-router';
import {useNotifications} from '../stores/notifications';

export function usePageAccessibility(main:Ref<HTMLElement|undefined>){
 const route=useRoute(),notifications=useNotifications();
 let pendingFocus=false,observer:MutationObserver|undefined;
 function update(){
  const heading=main.value?.querySelector<HTMLElement>('h1');
  const stateHeading=heading?undefined:main.value?.querySelector<HTMLElement>('.error-state h2, .empty h2');
  const fallback=String(route.meta.title||'');
  notifications.setPageTitle(heading?.textContent?.trim()||(stateHeading?(fallback?fallback+'：':'')+stateHeading.textContent?.trim():fallback));
  const target=heading||stateHeading;
  if(!target)return;
  target.tabIndex=-1;
  if(pendingFocus&&!target.closest('[inert]')){pendingFocus=false;target.focus()}
 }
 watch(()=>route.path,()=>{pendingFocus=true;notifications.setPageTitle(String(route.meta.title||''));void nextTick(update)},{flush:'post'});
 onMounted(()=>{
  update();
  observer=new MutationObserver(update);
  if(main.value)observer.observe(main.value,{childList:true,subtree:true,characterData:true});
 });
 onBeforeUnmount(()=>observer?.disconnect());
}

import {computed,ref,shallowRef,watch} from 'vue';
import {defineStore} from 'pinia';
import {api,type Notification,type NotificationSnapshot} from '../api/client';

type Notice={key:number,message:string,multiple:boolean};
type SyncTask={epoch:number,pending:boolean,announce:boolean,promise:Promise<void>};

export const useNotifications=defineStore('notifications',()=>{
 const items=shallowRef<Notification[]>([]),unreadCount=ref<number|null>(null),latestId=ref<string|null>(null);
 const loading=ref(false),error=ref(''),notice=shallowRef<Notice|null>(null),pageTitle=ref('');
 const badge=computed(()=>unreadCount.value===null?'':unreadCount.value>99?'99+':unreadCount.value>0?String(unreadCount.value):'');
 let userId:string|null=null,epoch=0,sequence=0,noticeKey=0,initialized=false,lastNoticeAt=0;
 let task:SyncTask|undefined;
 const liveEvents=new Set<string>();
 const knownIds=new Set<string>();
 watch([unreadCount,pageTitle],([count,title])=>{
  document.title=(count&&count>0?`（${count}条未读）`:'')+(title?title+' · ':'')+'校园集市';
 },{flush:'sync'});
 function setPageTitle(title:string){pageTitle.value=title}

 function dismiss(){notice.value=null}
 function setUser(id:string|null){
  if(id===userId)return task?.promise||Promise.resolve();
  epoch++;userId=id;task=undefined;initialized=false;
  liveEvents.clear();knownIds.clear();
  items.value=[];unreadCount.value=null;latestId.value=null;error.value='';loading.value=false;dismiss();
  return id?sync():Promise.resolve();
 }
 function sync(announce=true,eventId?:string):Promise<void>{
  if(!userId)return Promise.resolve();
  if(eventId&&/^[0-9]+$/.test(eventId)&&!knownIds.has(eventId))liveEvents.add(eventId);
  if(task){task.pending=true;task.announce ||= announce;return task.promise}
  const current:SyncTask={epoch,pending:true,announce,promise:Promise.resolve()};
  task=current;
  current.promise=drain(current).finally(()=>{
   if(task===current){
    task=undefined;loading.value=false;
    if(current.pending&&current.epoch===epoch)return sync(current.announce);
   }
  });
  return current.promise;
 }
 async function drain(current:SyncTask){
  while(current.pending&&current.epoch===epoch){
   current.pending=false;
   const announce=current.announce;current.announce=false;
   const request=++sequence,actor=userId;
   loading.value=unreadCount.value===null;
   try{
    const result=await api<NotificationSnapshot>('/notifications');
    if(current.epoch!==epoch||request!==sequence)return;
    if(result.items.some(item=>item.recipientId!==actor))throw new Error('账号已变化，请重新同步');
    const incoming=result.items.filter(item=>!item.isRead&&!knownIds.has(item.id)
     &&(initialized||liveEvents.has(item.id)));
    const outsideWindow=[...liveEvents].filter(id=>!knownIds.has(id)&&result.latestId!==null
     &&BigInt(id)<=BigInt(result.latestId)&&!result.items.some(item=>item.id===id));
    const countIncreased=initialized&&result.unreadCount>(unreadCount.value??0);
    if(announce&&(incoming.length||outsideWindow.length||countIncreased)){
     const newest=[...incoming].sort((a,b)=>BigInt(a.id)>BigInt(b.id)?-1:1)[0];
     const now=Date.now();
     notice.value={key:++noticeKey,message:newest?.message||'你有新的通知，请前往通知页查看。',multiple:incoming.length+outsideWindow.length>1||Boolean(notice.value&&now-lastNoticeAt<=2000)};
     lastNoticeAt=now;
    }
    // Read-status events must not consume a simultaneous, unannounced business event.
    if(announce||!initialized){
     initialized=true;
     for(const item of result.items)knownIds.add(item.id);
     for(const id of outsideWindow)knownIds.add(id);
     for(const id of liveEvents)if(knownIds.has(id))liveEvents.delete(id);
    }
    items.value=result.items;unreadCount.value=result.unreadCount;latestId.value=result.latestId;error.value='';
   }catch(failure){
    if(current.epoch!==epoch||request!==sequence)return;
    error.value='通知同步失败：'+(failure instanceof Error?failure.message:'请重试');
   }
  }
 }
 async function markRead(id:string){
  const version=epoch;
  await api('/notifications/'+encodeURIComponent(id)+'/read','POST',{});
  if(version===epoch)await sync(false);
 }
 async function markAllRead(){
  const throughId=latestId.value,version=epoch;
  if(throughId===null)return;
  await api('/notifications/read-all','POST',{throughId});
  if(version===epoch)await sync(false);
 }
 return {items,unreadCount,latestId,loading,error,notice,badge,setUser,sync,dismiss,markRead,markAllRead,setPageTitle};
});

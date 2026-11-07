import type { components } from './schema';
export type Actor=components['schemas']['Actor'];
export type Product=components['schemas']['Product'];
export type Order=components['schemas']['Order'];
export type Case=components['schemas']['Case'];
export type Report=components['schemas']['Report'];
export type Notification=components['schemas']['Notification'];
export type Session=components['schemas']['Session'];
let csrf='';
let recoverSession: (()=>Promise<void>)|undefined;
const unresolved=new Map<string,string>();
export class ApiError extends Error {constructor(public code:string,message:string,public status:number){super(message)}}
export function setCsrf(value:string){csrf=value}
export function setSessionRecovery(recover:()=>Promise<void>){recoverSession=recover}
export async function api<T>(path:string,method='GET',body?:unknown):Promise<T>{
 const signature=method+path+JSON.stringify(body??{}),write=method!=='GET';
 const key=unresolved.get(signature)||crypto.randomUUID();if(write)unresolved.set(signature,key);
 let response:Response;
 try{response=await fetch('/api/v1'+path,{method,credentials:'same-origin',headers:{...(body instanceof FormData?{}:{'Content-Type':'application/json'}),...(write?{'X-CSRF-TOKEN':csrf,'Idempotency-Key':key}:{})},body:body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body)});}catch{throw new ApiError('NETWORK','连接中断，请重试；本次操作会复用请求标识',0)}
 const data=await response.json();
 if(!response.ok){
  if(response.status<500)unresolved.delete(signature);
  if(path!=='/session'&&(data.code==='UNAUTHENTICATED'||data.code==='CSRF_REJECTED')){
   await recoverSession?.().catch(()=>{ /* 保留原始业务错误。 */ });
  }
  throw new ApiError(data.code||'REQUEST_FAILED',data.message||'请求失败，请重试',response.status);
 }
 unresolved.delete(signature);return data as T;
}
export const money=(cents:number)=>new Intl.NumberFormat('zh-CN',{style:'currency',currency:'CNY'}).format(cents/100);
export const date=(value:string|null|undefined)=>value?new Date(value).toLocaleString('zh-CN',{hour12:false}):'—';

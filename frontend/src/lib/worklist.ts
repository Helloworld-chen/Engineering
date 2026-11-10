import {computed} from 'vue';
import {useRoute,useRouter} from 'vue-router';
export function ordered<T extends {createdAt:string|null,id:string}>(items:T[],oldest=false){
 const time=(value:string|null)=>value&&Number.isFinite(Date.parse(value))?Date.parse(value):oldest?Infinity:-Infinity;
 return [...items].sort((a,b)=>(time(a.createdAt)-time(b.createdAt)||a.id.localeCompare(b.id))*(oldest?1:-1));
}
export const caseGroup=(status:string)=>status==='SUPPORT_REVIEW'?'pending':status==='CLOSED'?'closed':'active';
export function pageItems<T>(items:T[],value:unknown){
 const pages=Math.max(1,Math.ceil(items.length/20));
 const page=Math.min(pages,Math.max(1,Math.floor(Number(value)||1)));
 return {items:items.slice((page-1)*20,page*20),page,pages,total:items.length};
}
export function useListQuery(){
 const route=useRoute(),router=useRouter();
 const query=(key:string,fallback='')=>computed(()=>typeof route.query[key]==='string'?String(route.query[key]):fallback);
 function set(values:Record<string,string>){void router.replace({query:{...route.query,page:'1',...values}})}
 return {query,set,route};
}

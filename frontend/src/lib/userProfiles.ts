import type {LocationQuery} from 'vue-router';
export type ProfileContext='product'|'order'|'case'|'report';
export function profileLink(id:string,type:ProfileContext,contextId:string,from:string){return {path:'/users/'+encodeURIComponent(id),query:{contextType:type,contextId,from}}}
export function profileQuery(query:LocationQuery){const params=new URLSearchParams();for(const key of ['contextType','contextId'])if(typeof query[key]==='string')params.set(key,query[key]);return params}
export function pageNumber(value:unknown){const n=Number(value);return Number.isInteger(n)&&n>0?n:1}

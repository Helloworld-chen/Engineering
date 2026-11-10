export const isStaff=(role?:string)=>role==='ADMIN'||role==='SUPPORT';
export const homeFor=(role?:string)=>role==='ADMIN'?'/admin':role==='SUPPORT'?'/support':'/';
export function canVisit(role:string|undefined,path:string){
 // Match Vue Router's default case-insensitive and optional trailing slash semantics.
 path=path.toLowerCase().replace(/\/+$/,'')||'/';
 if(path==='/')return !isStaff(role);
 if(/^\/admin(?:\/|$)|^\/moderation(?:\/|$)/.test(path))return role==='ADMIN';
 if(/^\/support(?:\/|$)/.test(path))return role==='SUPPORT';
 if(path==='/publish'||path==='/my/products'||/^\/products\/[^/]+\/edit$/.test(path))return role==='USER';
 if(path==='/orders')return role==='USER';
 if(/^\/orders\/|^\/cases(?:\/|$)/.test(path))return role==='USER'||role==='SUPPORT';
 if(path==='/reports')return role==='USER'||role==='ADMIN';
 return true;
}
export function returnTo(value:unknown,role:string|undefined,fallback:string,paths:string[]){
 if(typeof value!=='string'||!/^\/(?![/\\])[^\\]*$/.test(value))return fallback;
 const path=value.split(/[?#]/)[0];
 return paths.some(prefix=>path===prefix||path.startsWith(prefix+'/'))&&canVisit(role,path)?value:fallback;
}

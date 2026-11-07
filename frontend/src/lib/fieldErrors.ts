import {nextTick,reactive} from 'vue';

export function useFieldErrors(){
 const fields=reactive<Record<string,string>>({});
 function clear(){for(const id of Object.keys(fields))delete fields[id]}
 function flag(id:string,message:string){
  fields[id]=message;
  void nextTick(()=>document.getElementById(id)?.focus());
 }
 function attributes(id:string){
  return fields[id]?{'aria-invalid':true,'aria-describedby':id+'-error'}:{};
 }
 return {fields,clear,flag,attributes};
}

// Element Plus owns keyboard trapping; this layer also isolates the accessibility tree.
const layers:HTMLElement[]=[];
const previous=new Map<HTMLElement,boolean>();
function restore(){for(const [node,inert] of previous)node.inert=inert;previous.clear()}
function sync(){
 restore();
 let branch=layers.at(-1);
 while(branch&&branch!==document.body){
  const parent=branch.parentElement;
  if(!parent)break;
  for(const sibling of parent.children){
   if(sibling!==branch&&sibling instanceof HTMLElement){previous.set(sibling,sibling.inert);sibling.inert=true}
  }
  branch=parent;
 }
}
export function isolateModal(node:HTMLElement){
 layers.push(node);sync();
 let released=false;
 return ()=>{
  if(released)return;released=true;
  const index=layers.indexOf(node);if(index>=0)layers.splice(index,1);
  sync();
 };
}

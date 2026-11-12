<script setup lang="ts">
import {nextTick,onBeforeUnmount,useId,watch} from 'vue';
import {ElDialog,ElDrawer} from 'element-plus';
import {isolateModal} from '../lib/modalIsolation';
defineOptions({inheritAttrs:false});
const props=withDefaults(defineProps<{modelValue:boolean,drawer?:boolean}>(),{drawer:false});
const emit=defineEmits<{'update:modelValue':[value:boolean]}>();
const modalClass='accessible-modal-'+useId().replaceAll(':','');
let release:(()=>void)|undefined,trigger:HTMLElement|null=null;
watch(()=>props.modelValue,value=>{if(value)trigger=document.activeElement as HTMLElement},{flush:'sync',immediate:true});
async function opened(){
 await nextTick();
 const overlay=document.querySelector<HTMLElement>('.'+modalClass);
 if(overlay&&props.modelValue&&!release)release=isolateModal(overlay);
}
function closed(){
 release?.();release=undefined;
 void nextTick(()=>{
  // Navigation or successful submission can remove the original trigger.
  if(document.activeElement===document.body||!(document.activeElement as HTMLElement)?.isConnected){
   const target=trigger?.isConnected&&!trigger.closest('[inert]')&&!trigger.matches(':disabled')?trigger:document.querySelector<HTMLElement>('main h1, main');
   if(target&&!target.closest('[inert]')){if(!target.hasAttribute('tabindex')&&target.matches('h1'))target.tabIndex=-1;target.focus()}
  }
 });
}
onBeforeUnmount(()=>{release?.();release=undefined});
</script>
<template>
 <component :is="drawer?ElDrawer:ElDialog" v-bind="$attrs" :model-value="modelValue" append-to-body :modal-class="modalClass" @update:model-value="emit('update:modelValue',$event)" @open="opened" @close-auto-focus="closed" @closed="closed">
  <template v-for="(_,name) in $slots" #[name]="scope"><slot :name="name" v-bind="scope||{}"/></template>
 </component>
</template>

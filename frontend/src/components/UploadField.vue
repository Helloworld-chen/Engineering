<script setup lang="ts">
import {nextTick,ref,useId} from 'vue';
import {api} from '../api/client';
import {PhUploadSimple} from '@phosphor-icons/vue';

const props=defineProps<{modelValue:string[],purpose:string,label:string,max?:number,inputId?:string,fieldError?:string}>();
const emit=defineEmits<{'update:modelValue':[value:string[]],busy:[value:boolean]}>();
const id=useId(),busy=ref(false),error=ref('');
async function upload(event:Event){
 const target=event.target as HTMLInputElement;
 const files=Array.from(target.files||[]);
 error.value='';
 if(props.modelValue.length+files.length>(props.max??6)){
  error.value=`最多上传 ${props.max??6} 张图片`;
  target.value='';
  target.focus();
  return;
 }
 busy.value=true;
 emit('busy',true);
 const ids=[...props.modelValue];
 try{
  for(const file of files){
   if(file.size>5000000)throw new Error('每张图片不能超过 5 MB');
   const form=new FormData();
   form.append('file',file);
   form.append('purpose',props.purpose);
   const item=await api<{id:string}>('/files','POST',form);
   ids.push(item.id);
   emit('update:modelValue',[...ids]);
  }
 }catch(failure){
  error.value=failure instanceof Error?failure.message:'图片上传失败，请重试';
 }finally{
  busy.value=false;
  emit('busy',false);
  target.value='';
  if(error.value)void nextTick(()=>target.focus());
 }
}
</script>
<template>
 <div class="upload-field">
  <label class="field-label" :for="inputId||id">{{label}} <span class="required">必填</span></label>
  <input :id="inputId||id" type="file" accept="image/jpeg,image/png,image/webp" multiple :disabled="busy||(max!==undefined&&modelValue.length>=max)" :aria-invalid="error||fieldError?true:undefined" :aria-describedby="(inputId||id)+'-hint'+(error||fieldError?' '+(inputId||id)+'-error':'')" @change="upload"/>
  <p :id="(inputId||id)+'-hint'" class="muted"><PhUploadSimple aria-hidden="true"/> JPEG、PNG、WebP，每张最多 5 MB{{busy?' · 正在上传':''}}</p>
  <div class="photo-strip"><div v-for="image in modelValue" :key="image">
   <img :src="'/api/v1/files/'+image" :alt="label+'预览'"/>
   <button type="button" class="text-button" :disabled="busy" @click="emit('update:modelValue',modelValue.filter(value=>value!==image))">移除</button>
  </div></div>
  <p v-if="error||fieldError" :id="(inputId||id)+'-error'" class="error" role="alert">{{error||fieldError}}</p>
 </div>
</template>

<script setup lang="ts">
import AccessibleDialog from './AccessibleDialog.vue';
import {ref,watch} from 'vue';
const props=defineProps<{title:string,description:string,busy:boolean,error:string,requiresReason?:boolean,reasonLabel?:string}>();
const open=defineModel<boolean>({required:true});
const emit=defineEmits<{confirm:[reason:string]}>();
const reason=ref('');
watch(open,value=>{if(value)reason.value=''});
</script>
<template>
 <AccessibleDialog v-model="open" :title="title" width="min(480px, calc(100% - 32px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
  <form class="stack" @submit.prevent="emit('confirm',reason.trim())">
   <p>{{description}}</p>
   <label v-if="requiresReason" class="field-label">{{reasonLabel||'处理理由'}}<textarea v-model="reason" required maxlength="2000" rows="4"/></label>
   <p v-if="error" class="error" role="alert">{{error}}</p>
   <div class="row end">
    <button class="button" type="button" :disabled="busy" @click="open=false">返回</button>
    <button class="button primary" type="submit" :disabled="busy||(requiresReason&&!reason.trim())">{{busy?'正在提交…':'确认'+title}}</button>
   </div>
  </form>
 </AccessibleDialog>
</template>

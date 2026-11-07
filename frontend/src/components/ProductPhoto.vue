<script setup lang="ts">
import {computed} from 'vue';
import {PhBookOpen,PhKeyboard,PhLamp,PhTennisBall,PhImage} from '@phosphor-icons/vue';
const props=defineProps<{image?:string,title:string,category:string}>();
const icons={图书:PhBookOpen,数码:PhKeyboard,生活:PhLamp,运动:PhTennisBall};
const icon=computed(()=>icons[props.category as keyof typeof icons]||PhImage);
</script>
<template>
 <div class="product-photo">
  <img v-if="image" :src="'/api/v1/files/'+image" :alt="title" />
  <div v-else class="photo-empty">
   <component :is="icon" :size="48" weight="thin" aria-hidden="true"/>
   <span>暂无实拍</span>
  </div>
 </div>
</template>

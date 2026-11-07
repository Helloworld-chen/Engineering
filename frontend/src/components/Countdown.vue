<script setup lang="ts">
import {ref,computed,onMounted,onUnmounted,watch} from 'vue';import {remainingMillis,formatDuration} from '../lib/time';
const p=defineProps<{deadline:string|null,serverTime:string,paused?:number|null}>();const elapsed=ref(0);let baseline=performance.now();let interval:ReturnType<typeof setInterval>;
watch(()=>[p.deadline,p.serverTime,p.paused],()=>{baseline=performance.now();elapsed.value=0});
onMounted(()=>{interval=setInterval(()=>elapsed.value=performance.now()-baseline,250)});onUnmounted(()=>clearInterval(interval));
const remaining=computed(()=>remainingMillis(p.deadline,p.serverTime,elapsed.value,p.paused));
</script>
<template><span class="countdown">{{paused!=null?'已暂停 · ':''}}{{formatDuration(remaining)}}<small v-if="remaining===0&&paused==null"> · 等待后端更新</small></span></template>

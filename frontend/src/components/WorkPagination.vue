<script setup lang="ts">
defineProps<{page:number,pages:number,total:number}>();defineEmits<{change:[page:number]}>();
</script>
<template><nav class="row spread work-pagination" aria-label="列表分页"><span class="muted">共 {{total}} 条 · 第 {{page}} / {{pages}} 页</span><div class="row"><button class="button" :disabled="page<=1" @click="$emit('change',page-1)">上一页</button><button class="button" :disabled="page>=pages" @click="$emit('change',page+1)">下一页</button></div></nav></template>

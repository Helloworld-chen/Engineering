<script setup lang="ts">import {label} from '../lib/labels';defineProps<{value:string}>();</script>
<template><span class="status" :class="{'status-done':['COMPLETED','APPROVED','SETTLED'].includes(value),'status-alert':['PENDING_PAYMENT','SUPPORT_REVIEW','BUYER_APPEAL'].includes(value)}">{{label(value)}}</span></template>

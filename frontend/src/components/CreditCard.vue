<script setup lang="ts">
import type {CreditSummary} from '../api/client';
defineProps<{title:string,credit:CreditSummary}>();
const levels=[5,4,3,2,1] as const;
</script>
<template><section class="panel stack" :aria-label="title"><div class="row spread"><h2>{{title}}</h2><span class="status">{{credit.grade}}</span></div><p v-if="credit.count" class="credit-average"><strong>{{credit.average?.toFixed(2)}}</strong><span> / 5 星</span></p><p v-else class="muted">暂无评价，完成交易后可获得对方评价。</p><p class="muted">{{credit.count}} 条评价</p><dl v-if="credit.count" class="rating-distribution"><template v-for="star in levels" :key="star"><dt>{{star}} 星</dt><dd><meter min="0" :max="credit.count" :value="credit.distribution[star]" :aria-label="star+'星评价数量'"/><span>{{credit.distribution[star]}} 条</span></dd></template></dl></section></template>

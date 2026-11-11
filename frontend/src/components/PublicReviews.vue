<script setup lang="ts">
import {date,type PublicReview} from '../api/client';
defineProps<{items:PublicReview[]}>();
</script>
<template><p v-if="!items.length" class="muted">暂无公开评价。</p><article v-for="item in items" :key="item.id" class="review-record stack section-divider"><div class="row spread"><strong>{{item.reviewerName}} · {{item.reviewerRole==='BUYER'?'买家':'卖家'}}</strong><span class="status" :aria-label="item.stars+'星评价'">{{item.stars}} / 5 星</span></div><p class="preserve">{{item.comment}}</p><p class="muted">{{item.productTitle}} · {{date(item.publishedAt)}}</p></article></template>

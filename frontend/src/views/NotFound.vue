<script setup lang="ts">
import {PhMapPin} from '@phosphor-icons/vue';
import {useSession} from '../stores/session';
import {isStaff,homeFor} from '../lib/access';
const session=useSession();
</script>
<template>
 <section class="panel empty not-found">
  <PhMapPin :size="40" weight="thin" aria-hidden="true"/>
  <div class="eyebrow">校园集市 · 404</div><h1>找不到这个页面</h1><p>{{isStaff(session.user?.role)?'链接可能已更改。你可以返回工作台继续处理待办。':'链接可能已更改。你可以返回集市继续浏览。'}}</p><RouterLink :to="homeFor(session.user?.role)" class="button primary">{{isStaff(session.user?.role)?'返回工作台':'返回商品集市'}}</RouterLink>
 </section>
</template>

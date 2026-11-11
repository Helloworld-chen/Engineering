// @vitest-environment jsdom
import {afterEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import CaseView from '../src/views/CaseView.vue';
import {useSession} from '../src/stores/session';
import {aftersale,order} from './sellerFixtures';
let wrapper:VueWrapper;
afterEach(()=>{wrapper?.unmount();vi.unstubAllGlobals()});
it('合并后售后详情同时保留双方资料入口、返回位置及三方留言',async()=>{
 setActivePinia(createPinia());useSession().user={id:'support',role:'SUPPORT',displayName:'客服',username:'support'};
 const item=aftersale('SUPPORT_REVIEW');
 const messages=[{id:'m1',senderId:'buyer',role:'BUYER',content:'请核对商品情况',createdAt:item.createdAt}];
 const fetcher=vi.fn(async(url:string,init?:RequestInit)=>{
  if(url.endsWith('/messages')){
   if(init?.method==='POST')messages.push({id:'m2',senderId:'support',role:'SUPPORT',content:JSON.parse(String(init.body)).content,createdAt:item.createdAt});
   return new Response(JSON.stringify({items:messages}));
  }
  return new Response(JSON.stringify(url.endsWith('/cases')?{items:[item]}:order('o',{cases:[item]})));
 });
 vi.stubGlobal('fetch',fetcher);
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/cases/:id',component:CaseView},{path:'/:pathMatch(.*)*',component:{template:'<div/>'}}]});
 await router.push('/cases/case?from=/cases?group=pending');
 wrapper=mount(CaseView,{global:{plugins:[router],stubs:{'el-dialog':true,'el-skeleton':true}}});await flushPromises();
 const links=wrapper.findAll('a').map(link=>link.attributes('href'));
 expect(links).toContain('/cases?group=pending');
 expect(links.some(link=>link.startsWith('/users/seller?')&&link.includes('contextType=case')&&link.includes('contextId=case'))).toBe(true);
 expect(links.some(link=>link.startsWith('/users/buyer?')&&link.includes('contextType=case'))).toBe(true);
 expect(wrapper.text()).toContain('请核对商品情况');
 await wrapper.get('input').setValue('请补充包装照片');
 await wrapper.findAll('button').find(button=>button.text()==='发送留言')!.trigger('click');await flushPromises();
 expect(wrapper.text()).toContain('请补充包装照片');
 expect(wrapper.get('input').element.value).toBe('');
 expect(fetcher.mock.calls.filter(([url,init])=>url.endsWith('/messages')&&init?.method==='POST')).toHaveLength(1);
});

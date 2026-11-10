// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import Dashboard from '../src/views/StaffDashboard.vue';
import Cases from '../src/views/CasesView.vue';
import {useSession} from '../src/stores/session';
import {product,aftersale} from './sellerFixtures';
let wrapper:VueWrapper;
beforeEach(()=>{setActivePinia(createPinia());useSession().user={id:'admin',role:'ADMIN',displayName:'管理员',username:'admin'}});
afterEach(()=>{wrapper?.unmount();vi.unstubAllGlobals()});
async function open(component:any,path:string){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component}]});await router.push(path);wrapper=mount(component,{global:{plugins:[router],stubs:{'el-skeleton':true,'el-button':{template:'<button @click="$emit(\'click\')"><slot/></button>'}}}});await flushPromises();return router}
it('管理员首页从全部列表统计，队列只列最早5条，举报失败可独立重试',async()=>{
 let failure=true;vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('/moderation/')?new Response(JSON.stringify({items:Array.from({length:7},(_,i)=>product(String(i)))})):new Response(JSON.stringify(failure?{message:'举报读取失败'}:{items:[]}),{status:failure?503:200})));
 await open(Dashboard,'/admin');expect(wrapper.findAll('.work-stat strong').map(e=>e.text())).toEqual(['7','—']);expect(wrapper.findAll('article')).toHaveLength(5);
 expect(wrapper.get('[role="alert"]').text()).toContain('举报读取失败');failure=false;await wrapper.get('[role="alert"] button').trigger('click');await flushPromises();expect(wrapper.findAll('.work-stat strong').map(e=>e.text())).toEqual(['7','0']);
});
it('客服分组与20条分页保留参数，详情链接携带返回位置',async()=>{
 useSession().user={id:'support',role:'SUPPORT',displayName:'客服',username:'support'};
 const rows=Array.from({length:25},(_,i)=>({...aftersale('SUPPORT_REVIEW'),id:String(i).padStart(2,'0')}));
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({items:rows}))));
 const router=await open(Cases,'/cases?group=pending&page=2&kind=REFUND');expect(wrapper.findAll('.record')).toHaveLength(5);expect(wrapper.get('.record').attributes('href')).toContain('from=/cases?');
 await wrapper.get('input').setValue('00');await flushPromises();expect(router.currentRoute.value.query.page).toBe('1');expect(wrapper.findAll('.record')).toHaveLength(1);
});

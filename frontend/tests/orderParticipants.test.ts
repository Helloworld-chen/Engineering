// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import OrderView from '../src/views/OrderView.vue';
import {useSession} from '../src/stores/session';
import {order} from './sellerFixtures';
let wrapper:VueWrapper;
let result:ReturnType<typeof order>&{buyerName?:string,sellerName?:string};
beforeEach(()=>{setActivePinia(createPinia());result={...order('o',{status:'AWAITING_SHIPMENT',fundsStatus:'HELD'}),buyerName:'陈同学',sellerName:'林同学'};vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify(result))))});
afterEach(()=>{wrapper?.unmount();vi.unstubAllGlobals()});
async function open(id:string,role:'USER'|'SUPPORT'='USER'){
 useSession().user={id,role,username:id,displayName:id};
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:OrderView}]});await router.push('/orders/o');
 wrapper=mount(OrderView,{global:{plugins:[router],stubs:{'el-dialog':true,'el-skeleton':true}}});await flushPromises();
}
for(const id of ['buyer','seller','support'])it(`${id}能看到正确的交易双方及本人标记`,async()=>{
 await open(id,id==='support'?'SUPPORT':'USER');
 const parties=wrapper.get('[aria-label="交易双方"]');expect(parties.text()).toContain('卖家');expect(parties.text()).toContain('林同学');expect(parties.text()).toContain('买家');expect(parties.text()).toContain('陈同学');
 expect(parties.get('[data-party="seller"]').text()).toContain('用户编号 seller');expect(parties.get('[data-party="buyer"]').text()).toContain('用户编号 buyer');
 expect(parties.get(`[data-party="${id==='seller'?'seller':'buyer'}"]`).text().includes('（你）')).toBe(id!=='support');
 if(id==='support')expect(parties.text()).not.toContain('（你）');
});
it('旧响应缺少昵称时保留身份编号，不显示undefined',async()=>{
 delete result.sellerName;delete result.buyerName;
 await open('buyer');const parties=wrapper.get('[aria-label="交易双方"]');expect(parties.text()).toContain('用户编号 seller');expect(parties.text()).not.toContain('undefined');
});

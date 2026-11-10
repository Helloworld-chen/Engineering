// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {flushPromises,mount,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import MyProducts from '../src/views/MyProductsView.vue';
import {useSession} from '../src/stores/session';
import {product,order} from './sellerFixtures';

let wrapper:VueWrapper;
let products=[product()];
let orders=[order()];
let failOrders=false;
beforeEach(()=>{
 products=[product()];orders=[order()];failOrders=false;
 setActivePinia(createPinia());
 useSession().user={id:'seller',username:'seller',displayName:'林同学',role:'USER'};
 vi.stubGlobal('fetch',vi.fn(async(url:string)=>{
  if(url==='/api/v1/my/products')return new Response(JSON.stringify({items:products}));
  if(url==='/api/v1/orders?side=selling')return new Response(JSON.stringify(failOrders?{code:'ERROR',message:'订单读取失败'}:{items:orders}),{status:failOrders?503:200});
  throw new Error('Unexpected request: '+url);
 }));
});
afterEach(()=>{wrapper?.unmount();vi.unstubAllGlobals()});
async function open(){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:MyProducts}]});
 await router.push('/my/products');await router.isReady();
 wrapper=mount(MyProducts,{global:{plugins:[router],stubs:{'el-skeleton':true,'el-button':{template:'<button @click="$emit(\'click\')"><slot/></button>'}}}});
 await flushPromises();
}
it('下单后显示对应订单入口，实时付款更新后显示去发货',async()=>{
 await open();
 expect(wrapper.text()).toContain('买家已下单，等待付款');
 expect(wrapper.find('a[href="/orders/o"]').text()).toBe('查看订单');
 orders=[order('o',{status:'AWAITING_SHIPMENT',fundsStatus:'HELD',allowedActions:['SHIP']})];
 products=[product('p',{saleStatus:'SOLD'})];
 window.dispatchEvent(new Event('market:changed'));await flushPromises();
 expect(wrapper.text()).toContain('买家已付款，待你发货');
 expect(wrapper.find('a[href="/orders/o"]').text()).toBe('去发货');
 expect(wrapper.text()).not.toContain('编辑');
});
it('订单读取失败时显示错误，可重试恢复，不能误报空商品',async()=>{
 failOrders=true;await open();
 expect(wrapper.get('[role="alert"]').text()).toContain('订单读取失败');
 expect(wrapper.text()).not.toContain('还没有发布商品');
 failOrders=false;
 await wrapper.get('[role="alert"] button').trigger('click');await flushPromises();
 expect(wrapper.text()).toContain('买家已下单，等待付款');
});
it('账号变更清除旧商品并重新读取',async()=>{
 await open();
 products=[product('new',{title:'陈同学商品',sellerId:'buyer'})];orders=[];
 useSession().user={id:'buyer',username:'buyer',displayName:'陈同学',role:'USER'};
 await flushPromises();
 expect(wrapper.text()).toContain('陈同学商品');
 expect(wrapper.text()).not.toContain('校园笔记本 p');
 expect(wrapper.find('a[href="/orders/o"]').exists()).toBe(false);
});

// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia,disposePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import UserProfile from '../src/views/UserProfileView.vue';
import OrderReviews from '../src/components/OrderReviews.vue';
import {useSession} from '../src/stores/session';
let wrapper:VueWrapper;let pinia:ReturnType<typeof createPinia>;
const credit={count:0,average:null,grade:'暂无评价',distribution:{'1':0,'2':0,'3':0,'4':0,'5':0}};
const profile={id:'seller',displayName:'林同学',avatarId:null,avatarUrl:null,school:'',campus:'',bio:'',gender:'',buyerCredit:credit,sellerCredit:{...credit,count:1,average:5,grade:'优秀',distribution:{...credit.distribution,'5':1}},context:null};
const snapshot={eligible:true,reason:'',deadline:'2026-11-17T12:00:00Z',serverTime:'2026-11-10T12:00:00Z',ownReview:null,items:[]};
const response=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status});
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);useSession().user={id:'buyer',role:'USER',displayName:'陈同学',username:'buyer'}});
afterEach(()=>{wrapper?.unmount();disposePinia(pinia);vi.unstubAllGlobals()});
async function open(component:any,path:string,props={}){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div/>'}}]});await router.push(path);wrapper=mount(component,{props,global:{plugins:[router],stubs:{'el-skeleton':true,'el-button':{template:'<button @click="$emit(\'click\')"><slot/></button>'},'el-dialog':{props:['modelValue'],template:'<div v-if="modelValue" role="dialog"><slot/></div>'}}}});await flushPromises();return router}
it('用户资料按业务上下文读取，首条评价展示等级，缺失资料明确展示',async()=>{
 const fetch=vi.fn(async(url:string)=>response(url.includes('/profile?')?profile:{items:[],total:0,page:1,pageSize:20}));vi.stubGlobal('fetch',fetch);
 await open(UserProfile,'/users/seller?contextType=order&contextId=order1&from=/orders/order1');
 expect(wrapper.get('h1').text()).toBe('林同学');expect(wrapper.text()).toContain('未填写');expect(wrapper.get('[aria-label="卖家信用"]').text()).toContain('优秀');expect(wrapper.get('[aria-label="卖家信用"]').text()).toContain('1 条评价');expect(wrapper.get('[aria-label="买家信用"]').text()).toContain('暂无评价');
 for(const [url] of fetch.mock.calls){expect(url).toContain('contextType=order');expect(url).toContain('contextId=order1')}
 expect(wrapper.get('.back-link').attributes('href')).toBe('/orders/order1');
});
it('资料读取失败可重试，错误不会被展示成空用户资料',async()=>{
 let fail=true;vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.includes('/profile?')?response(fail?{message:'用户资料读取失败'}:profile,fail?503:200):response({items:[],total:0,page:1,pageSize:20})));
 await open(UserProfile,'/users/seller?contextType=product&contextId=p1');expect(wrapper.get('[role="alert"]').text()).toContain('读取失败');expect(wrapper.find('h1').exists()).toBe(false);
 fail=false;await wrapper.get('[role="alert"] button').trigger('click');await flushPromises();expect(wrapper.get('h1').text()).toBe('林同学');
});
it('评价在确认后提交，提交一次后展示本人待公开内容并移除表单',async()=>{
 let submitted=false;const fetch=vi.fn(async(_url:string,init:RequestInit)=>{if(init?.method==='POST'){submitted=true;expect(JSON.parse(init.body as string)).toEqual({stars:4,comment:'沟通及时'});}return response(submitted?{...snapshot,eligible:false,reason:'已提交评价',ownReview:{id:'r1',stars:4,comment:'沟通及时',submittedAt:'2026-11-10T12:00:00Z',publishedAt:null}}:snapshot)});vi.stubGlobal('fetch',fetch);
 await open(OrderReviews,'/orders/o1',{orderId:'o1'});await wrapper.get('input[value="4"]').setValue(true);await wrapper.get('textarea').setValue('沟通及时');await wrapper.get('form').trigger('submit');await flushPromises();expect(submitted).toBe(false);expect(wrapper.get('[role="dialog"]').text()).toContain('提交后不可修改');
 await wrapper.get('[data-confirm-review]').trigger('click');await flushPromises();expect(submitted).toBe(true);expect(wrapper.text()).toContain('待公开');expect(wrapper.text()).toContain('沟通及时');expect(wrapper.find('textarea').exists()).toBe(false);
});
it('到期或客服只读快照不显示评价表单，不自行生成对方未公开内容',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>response({...snapshot,eligible:false,reason:'评价期限已结束'})));await open(OrderReviews,'/orders/o1',{orderId:'o1'});expect(wrapper.find('textarea').exists()).toBe(false);expect(wrapper.text()).toContain('评价期限已结束');expect(wrapper.text()).toContain('暂无公开评价');
});
it('评价长度按Unicode码点计算，500个非BMP字符可确认，501个被拒绝',async()=>{
 vi.stubGlobal('fetch',vi.fn(async()=>response(snapshot)));await open(OrderReviews,'/orders/o1',{orderId:'o1'});
 await wrapper.get('input[value="5"]').setValue(true);await wrapper.get('textarea').setValue('😀'.repeat(501));await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.text()).toContain('评价内容最多500字');expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
 await wrapper.get('textarea').setValue('😀'.repeat(500));await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.get('[role="dialog"]').text()).toContain('提交后不可修改');
});

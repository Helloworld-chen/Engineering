// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia,disposePinia} from 'pinia';
import {createMemoryHistory,createRouter} from 'vue-router';
import NotificationAlerts from '../src/components/NotificationAlerts.vue';
import {useNotifications} from '../src/stores/notifications';
let wrapper:VueWrapper;
let pinia:ReturnType<typeof createPinia>;
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);vi.useFakeTimers()});
afterEach(()=>{wrapper?.unmount();disposePinia(pinia);vi.useRealTimers()});
async function open(){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div/>'}},{path:'/notifications',component:{template:'<div/>'}}]});
 await router.push('/');await router.isReady();
 wrapper=mount(NotificationAlerts,{attachTo:document.body,global:{plugins:[router]}});
 const store=useNotifications();store.notice={key:1,message:'买家已付款，请及时发货',multiple:false};
 await flushPromises();return store;
}
it('显示可访问消息和通知链接，8秒后关闭且不清除未读数',async()=>{
 const store=await open();store.unreadCount=1;
 expect(wrapper.get('[role="status"]').text()).toContain('买家已付款，请及时发货');
 expect(wrapper.get('a').attributes('href')).toBe('/notifications');
 await vi.advanceTimersByTimeAsync(7999);expect(store.notice).not.toBeNull();
 await vi.advanceTimersByTimeAsync(1);expect(store.notice).toBeNull();expect(store.unreadCount).toBe(1);
});
it('悬停或键盘聚焦暂停倒计时，离开后继续剩余时间',async()=>{
 const store=await open();await vi.advanceTimersByTimeAsync(3000);
 await wrapper.get('.notification-toast').trigger('mouseenter');await vi.advanceTimersByTimeAsync(10000);
 expect(store.notice).not.toBeNull();
 const button=wrapper.get('button');(button.element as HTMLButtonElement).focus();
 await wrapper.get('.notification-toast').trigger('mouseleave');await vi.advanceTimersByTimeAsync(10000);
 expect(store.notice).not.toBeNull();
 (button.element as HTMLButtonElement).blur();await flushPromises();
 await vi.advanceTimersByTimeAsync(4999);expect(store.notice).not.toBeNull();
 await vi.advanceTimersByTimeAsync(1);expect(store.notice).toBeNull();
});
it('关闭和查看入口都不自动调用已读接口，新消息重置展示时长',async()=>{
 const store=await open();const read=vi.spyOn(store,'markRead');
 await vi.advanceTimersByTimeAsync(7000);
 store.notice={key:2,message:'售后有新进展',multiple:true};await flushPromises();
 await vi.advanceTimersByTimeAsync(2000);expect(store.notice).not.toBeNull();
 expect(wrapper.text()).toContain('收到多条新消息');
 await wrapper.get('button').trigger('click');expect(store.notice).toBeNull();expect(read).not.toHaveBeenCalled();
});

// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {defineComponent} from 'vue';
import {createPinia,setActivePinia} from 'pinia';
import {useSession} from '../src/stores/session';
import {useWorkLoad} from '../src/lib/workLoad';
let wrapper:VueWrapper;
const deferred=()=>{let resolve!:(s:string)=>void;const promise=new Promise<string>(r=>resolve=r);return {promise,resolve}};
beforeEach(()=>{vi.useFakeTimers();setActivePinia(createPinia());useSession().user={id:'admin',role:'ADMIN',displayName:'管理员',username:'admin'}});
afterEach(()=>{wrapper?.unmount();vi.useRealTimers();vi.restoreAllMocks()});
function open(loader:()=>Promise<string>){let state!:ReturnType<typeof useWorkLoad<string>>;wrapper=mount(defineComponent({setup(){state=useWorkLoad(loader);return{}},template:'<div/>'}));return state}
it('请求期间多个事件合并为一次补查，旧账号迟到响应不得覆盖新数据',async()=>{
 const a=deferred(),b=deferred(),c=deferred();const load=vi.fn().mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise).mockReturnValueOnce(c.promise);const state=open(load);
 window.dispatchEvent(new Event('market:changed'));window.dispatchEvent(new Event('focus'));
 expect(load).toHaveBeenCalledTimes(1);a.resolve('old');await flushPromises();expect(load).toHaveBeenCalledTimes(2);
 useSession().user={id:'support',role:'SUPPORT',displayName:'客服',username:'support'};
 expect(state.data.value).toBeUndefined();expect(load).toHaveBeenCalledTimes(3);
 c.resolve('new');await flushPromises();b.resolve('late');await flushPromises();expect(state.data.value).toBe('new');
});
it('只在可见页面每30秒补查，恢复可见即刷新，卸载停止',async()=>{
 let hidden=false;vi.spyOn(document,'visibilityState','get').mockImplementation(()=>hidden?'hidden':'visible');
 const load=vi.fn().mockResolvedValue('ok');open(load);await flushPromises();await vi.advanceTimersByTimeAsync(30000);expect(load).toHaveBeenCalledTimes(2);
 hidden=true;document.dispatchEvent(new Event('visibilitychange'));await vi.advanceTimersByTimeAsync(60000);expect(load).toHaveBeenCalledTimes(2);
 hidden=false;document.dispatchEvent(new Event('visibilitychange'));await flushPromises();expect(load).toHaveBeenCalledTimes(3);
 wrapper.unmount();await vi.advanceTimersByTimeAsync(60000);expect(load).toHaveBeenCalledTimes(3);
});
it('失败保留可信数据，首次失败保持未知，重试可恢复',async()=>{
 const load=vi.fn().mockRejectedValueOnce(new Error('读取失败')).mockResolvedValueOnce('good').mockRejectedValueOnce(new Error('断线'));
 const state=open(load);await flushPromises();expect(state.data.value).toBeUndefined();expect(state.error.value).toBe('读取失败');
 await state.reload();expect(state.data.value).toBe('good');await state.reload();expect(state.data.value).toBe('good');expect(state.error.value).toBe('断线');
});
it('同账号快速改变筛选时丢弃旧筛选响应并补查最新条件',async()=>{
 let key='A';const a=deferred(),b=deferred();const loader=vi.fn().mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);let state!:ReturnType<typeof useWorkLoad<string>>;
 wrapper=mount(defineComponent({setup(){state=useWorkLoad(loader,false,()=>key);return{}},template:'<div/>'}));
 key='B';void state.reload();a.resolve('A的结果');await flushPromises();expect(state.data.value).toBeUndefined();expect(loader).toHaveBeenCalledTimes(2);b.resolve('B的结果');await flushPromises();expect(state.data.value).toBe('B的结果');
});

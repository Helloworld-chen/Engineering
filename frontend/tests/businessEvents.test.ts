// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia,disposePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import App from '../src/App.vue';
import {useSession} from '../src/stores/session';
import {useNotifications} from '../src/stores/notifications';
class Stream extends EventTarget {
 static instances:Stream[]=[];onopen:(()=>void)|null=null;onerror:(()=>void)|null=null;
 constructor(){super();Stream.instances.push(this)}
 close(){}
}
let wrapper:VueWrapper;let pinia:ReturnType<typeof createPinia>;
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);Stream.instances=[];vi.stubGlobal('EventSource',Stream);
 vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({items:[],unreadCount:0,latestId:null}))));
 useSession().user={id:'seller',role:'USER',displayName:'林同学',username:'seller'};
});
afterEach(()=>{wrapper?.unmount();disposePinia(pinia);vi.unstubAllGlobals();vi.restoreAllMocks()});
async function open(){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div/>'}}]});await router.push('/');wrapper=mount(App,{global:{plugins:[router],stubs:{'el-config-provider':{template:'<div><slot/></div>'},'el-button':true,'el-drawer':true}}});await flushPromises()}
it('业务刷新事件更新页面但不查询通知、不增加未读或弹窗',async()=>{
 await open();const notices=useNotifications();const sync=vi.spyOn(notices,'sync');const refresh=vi.spyOn(useSession(),'refresh').mockResolvedValue();
 const changed=vi.fn();window.addEventListener('market:changed',changed);
 try{Stream.instances[0].dispatchEvent(new MessageEvent('business-changed',{data:'{"aggregateId":"order"}'}));await flushPromises();
 expect(changed).toHaveBeenCalledOnce();expect(sync).not.toHaveBeenCalled();expect(notices.unreadCount).toBe(0);expect(notices.notice).toBeNull();expect(document.title).toBe('校园集市');expect(refresh).toHaveBeenCalledOnce();
 }finally{window.removeEventListener('market:changed',changed)}
});
it('切换账号或退出后旧连接的业务事件被忽略，恢复连接仍同步通知',async()=>{
 await open();const old=Stream.instances[0];useSession().user={id:'buyer',role:'USER',displayName:'陈同学',username:'buyer'};await flushPromises();
 const changed=vi.fn();window.addEventListener('market:changed',changed);const sync=vi.spyOn(useNotifications(),'sync');vi.spyOn(useSession(),'refresh').mockResolvedValue();
 try{old.dispatchEvent(new MessageEvent('business-changed'));expect(changed).not.toHaveBeenCalled();
 Stream.instances[1].onopen?.();expect(changed).toHaveBeenCalledOnce();expect(sync).toHaveBeenCalled();
 useSession().user=null;await flushPromises();changed.mockClear();Stream.instances[1].dispatchEvent(new MessageEvent('business-changed'));expect(changed).not.toHaveBeenCalled();
 }finally{window.removeEventListener('market:changed',changed)}
});

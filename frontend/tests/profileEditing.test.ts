// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils';
import {createPinia,setActivePinia,disposePinia} from 'pinia';
import {createRouter,createMemoryHistory} from 'vue-router';
import Profile from '../src/views/ProfileView.vue';
import UploadField from '../src/components/UploadField.vue';
import {useSession} from '../src/stores/session';
let wrapper:VueWrapper;let pinia:ReturnType<typeof createPinia>;
const profile={id:'buyer',displayName:'陈同学',avatarId:null,avatarUrl:null,school:'',campus:'',bio:'',gender:''};
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);useSession().user={id:'buyer',role:'USER',displayName:'陈同学',username:'buyer'}});
afterEach(()=>{wrapper?.unmount();disposePinia(pinia);vi.unstubAllGlobals()});
it('既有凭证上传默认标注必填，头像明确选填',()=>{wrapper=mount(UploadField,{props:{modelValue:[],label:'商品凭证',purpose:'GOODS'}});expect(wrapper.text()).toContain('必填')});
it('头像上传组件卸载会释放父组件busy状态',async()=>{let finish!:(r:Response)=>void;vi.stubGlobal('fetch',vi.fn(()=>new Promise<Response>(resolve=>{finish=resolve})));wrapper=mount(UploadField,{props:{modelValue:[],label:'头像',purpose:'AVATAR',required:false}});const input=wrapper.get('input').element as HTMLInputElement;Object.defineProperty(input,'files',{value:[new File(['png'],'avatar.png',{type:'image/png'})]});await wrapper.get('input').trigger('change');expect(wrapper.emitted('busy')?.at(-1)).toEqual([true]);wrapper.unmount();expect(wrapper.emitted('busy')?.at(-1)).toEqual([false]);finish(new Response(JSON.stringify({id:'f1'})));await flushPromises()});
it('资料提交期间锁定输入，避免响应覆盖新输入',async()=>{let finish!:(r:Response)=>void;vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit)=>{if(init.method==='PATCH')return new Promise<Response>(resolve=>{finish=resolve});return new Response(JSON.stringify(url.endsWith('/session')?{user:useSession().user}:profile))}));const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div/>'}}]});await router.push('/profile');wrapper=mount(Profile,{global:{plugins:[router],stubs:{'el-skeleton':true,'el-button':true,'el-dialog':true}}});await flushPromises();await wrapper.get('form').trigger('submit');expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();finish(new Response(JSON.stringify({profile})));await flushPromises();expect(wrapper.get('fieldset').attributes('disabled')).toBeUndefined()});

import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {createPinia,setActivePinia} from 'pinia';
import {api,type Session} from '../src/api/client';
import {useSession} from '../src/stores/session';

const buyer={id:'buyer',username:'buyer',displayName:'买家',role:'USER'} as const;
const authenticated:Session={user:buyer,balance:20000,csrfToken:'old-token',demo:true};
const anonymous:Session={user:null,balance:null,csrfToken:'new-token',demo:true};
const json=(status:number,body:unknown)=>new Response(JSON.stringify(body),{status});

beforeEach(()=>setActivePinia(createPinia()));
afterEach(()=>vi.unstubAllGlobals());

describe('会话恢复',()=>{
 it('退出响应丢失后查询真实身份并清除已退出用户',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(json(200,authenticated))
   .mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(json(200,anonymous));
  vi.stubGlobal('fetch',fetch);
  const session=useSession();
  await session.refresh();
  await expect(session.logout()).resolves.toBeUndefined();
  expect(session.user).toBeNull();
  expect(fetch.mock.calls[2][0]).toBe('/api/v1/session');
 });

 it.each([[401,'UNAUTHENTICATED'],[403,'CSRF_REJECTED']] as const)('身份边界错误 %s 后重新查询会话',async(status,code)=>{
  const fetch=vi.fn().mockResolvedValueOnce(json(200,authenticated))
   .mockResolvedValueOnce(json(status,{code,message:'身份失效'}))
   .mockResolvedValueOnce(json(200,anonymous));
  vi.stubGlobal('fetch',fetch);
  const session=useSession();
  await session.refresh();
  await expect(api('/profile','PATCH',{displayName:'新昵称'})).rejects.toMatchObject({code});
  expect(session.user).toBeNull();
  expect(fetch.mock.calls[2][0]).toBe('/api/v1/session');
 });

 it('旧请求晚返回时保留新会话和新 CSRF 令牌',async()=>{
  let finish!: (response:Response)=>void;
  const older=new Promise<Response>(resolve=>{finish=resolve});
  const fetch=vi.fn().mockReturnValueOnce(older)
   .mockResolvedValueOnce(json(200,anonymous)).mockResolvedValueOnce(json(200,{}));
  vi.stubGlobal('fetch',fetch);
  const session=useSession();
  const pending=session.refresh();
  await session.refresh();
  finish(json(200,authenticated));
  await pending;
  expect(session.user).toBeNull();
  await api('/auth/login','POST',{username:'buyer',password:'test-password'});
  expect(new Headers(fetch.mock.calls[2][1].headers).get('X-CSRF-TOKEN')).toBe('new-token');
 });
});

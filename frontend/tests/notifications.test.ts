// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {createPinia,setActivePinia,disposePinia} from 'pinia';
import {useNotifications} from '../src/stores/notifications';

const message=(id:string,user='seller',isRead=false)=>({id,recipientId:user,aggregateId:'order',message:'消息 '+id,createdAt:'2026-11-09T00:00:00Z',isRead});
const snapshot=(id:string|null,count=1,user='seller')=>({items:id?[message(id,user)]:[],unreadCount:count,latestId:id});
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status});
let pinia:ReturnType<typeof createPinia>;
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);document.title='校园集市'});
afterEach(()=>{disposePinia(pinia);vi.unstubAllGlobals()});

it('首次登录只展示全量未读数，退出清空标题和提醒',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(snapshot('3',150))));
 const store=useNotifications();await store.setUser('seller');
 expect(store.unreadCount).toBe(150);expect(store.badge).toBe('99+');
 expect(document.title).toBe('（150条未读）校园集市');expect(store.notice).toBeNull();
 await store.setUser(null);
 expect(store.unreadCount).toBeNull();expect(document.title).toBe('校园集市');expect(store.items).toEqual([]);
});
it('新消息提醒一次，重复事件不重复，连续新消息合并',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(response(snapshot('1'))).mockResolvedValueOnce(response(snapshot('2',2)))
  .mockResolvedValueOnce(response(snapshot('2',2))).mockResolvedValueOnce(response(snapshot('3',3)));
 vi.stubGlobal('fetch',fetch);
 const store=useNotifications();await store.setUser('seller');await store.sync(true,'2');
 expect(store.notice?.message).toBe('消息 2');const key=store.notice?.key;
 await store.sync(true,'2');expect(store.notice?.key).toBe(key);
 await store.sync(true,'3');expect(store.notice).toMatchObject({message:'消息 3',multiple:true});
 store.dismiss();expect(store.notice).toBeNull();expect(store.unreadCount).toBe(3);
});
it('已读同步不弹提醒且不会吞掉同时到达的新消息',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(snapshot('1')))
  .mockResolvedValueOnce(response(snapshot('2'))).mockResolvedValueOnce(response(snapshot('2'))));
 const store=useNotifications();await store.setUser('seller');await store.sync(false);
 expect(store.notice).toBeNull();await store.sync(true,'2');expect(store.notice?.message).toBe('消息 2');
});
it('初始化期间收到的消息会补查，并合并并发刷新',async()=>{
 let finish!:(r:Response)=>void;
 const fetch=vi.fn().mockReturnValueOnce(new Promise<Response>(resolve=>finish=resolve))
  .mockResolvedValueOnce(response(snapshot('2',2)));
 vi.stubGlobal('fetch',fetch);
 const store=useNotifications();const first=store.setUser('seller');
 const second=store.sync(true,'2');const third=store.sync();
 expect(fetch).toHaveBeenCalledTimes(1);
 finish(response(snapshot('1')));await Promise.all([first,second,third]);
 expect(fetch).toHaveBeenCalledTimes(2);expect(store.notice?.message).toBe('消息 2');
});
it('首次失败保持未知，之后失败保留可信未读数，重试恢复',async()=>{
 const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(response(snapshot('1',7)))
  .mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(response(snapshot('2',8)));
 vi.stubGlobal('fetch',fetch);const store=useNotifications();await store.setUser('seller');
 expect(store.unreadCount).toBeNull();expect(store.error).toContain('通知同步失败');
 await store.sync();expect(store.unreadCount).toBe(7);expect(store.notice).toBeNull();
 await store.sync();expect(store.unreadCount).toBe(7);expect(document.title).toBe('（7条未读）校园集市');
 await store.sync();expect(store.unreadCount).toBe(8);expect(store.error).toBe('');expect(store.notice?.message).toBe('消息 2');
});
it('新账号先返回、旧账号请求迟到时不会回流旧通知',async()=>{
 let finish!:(r:Response)=>void;
 vi.stubGlobal('fetch',vi.fn().mockReturnValueOnce(new Promise<Response>(resolve=>finish=resolve))
  .mockResolvedValueOnce(response(snapshot('20',4,'buyer'))));
 const store=useNotifications();const old=store.setUser('seller');await store.setUser('buyer');
 finish(response(snapshot('99',99)));await old;
 expect(store.items[0]?.recipientId).toBe('buyer');expect(store.unreadCount).toBe(4);expect(store.notice).toBeNull();
 expect(document.title).toBe('（4条未读）校园集市');
});
it('批量已读提交点击时的编号，刷新后保留新到未读',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(response(snapshot('9007199254740993',110)))
  .mockResolvedValueOnce(response({success:true})).mockResolvedValueOnce(response(snapshot('9007199254740994')));
 vi.stubGlobal('fetch',fetch);const store=useNotifications();await store.setUser('seller');await store.markAllRead();
 expect(fetch.mock.calls[1][0]).toBe('/api/v1/notifications/read-all');
 expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({throughId:'9007199254740993'});
 expect(store.unreadCount).toBe(1);expect(store.notice).toBeNull();
});
it('已读事件更新计数，空收件箱恢复标题',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(snapshot('1')))
  .mockResolvedValueOnce(response({items:[message('1','seller',true)],unreadCount:0,latestId:'1'})));
 const store=useNotifications();await store.setUser('seller');await store.sync(false);
 expect(store.badge).toBe('');expect(document.title).toBe('校园集市');expect(store.notice).toBeNull();
});
it('初始化快照中已有的通知事件再次抵达时不弹历史提醒',async()=>{
 vi.stubGlobal('fetch',vi.fn().mockImplementation(async()=>response(snapshot('10',3))));
 const store=useNotifications();await store.setUser('seller');await store.sync(true,'10');
 expect(store.notice).toBeNull();
});
it.each([true,false])('较小编号晚提交时仍提醒一次，含断线补查（有事件=%s）',async(withEvent)=>{
 const delayed={items:[message('10'),message('9')],unreadCount:2,latestId:'10'};
 vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(response(snapshot('10')))
  .mockResolvedValueOnce(response(delayed)).mockResolvedValueOnce(response(delayed)));
 const store=useNotifications();await store.setUser('seller');await store.sync(true,withEvent?'9':undefined);
 expect(store.notice?.message).toBe('消息 9');const key=store.notice?.key;
 await store.sync(true,'9');expect(store.notice?.key).toBe(key);
});
it('晚到事件已落在最近100条之外时提供合并提示，不重复提醒',async()=>{
 const latest={items:[message('200')],unreadCount:150,latestId:'200'};
 vi.stubGlobal('fetch',vi.fn().mockImplementation(async()=>response(latest)));
 const store=useNotifications();await store.setUser('seller');await store.sync(true,'9');
 expect(store.notice?.message).toBe('你有新的通知，请前往通知页查看。');const key=store.notice?.key;
 await store.sync(true,'9');expect(store.notice?.key).toBe(key);
});

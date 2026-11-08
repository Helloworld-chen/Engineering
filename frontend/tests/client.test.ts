import {afterEach,describe,expect,it,vi} from 'vitest';
import {api,setCsrf} from '../src/api/client';

function response(status:number,data:unknown){
 return new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
}

function key(fetch:ReturnType<typeof vi.fn>,index:number){
 const headers=new Headers(fetch.mock.calls[index][1].headers);
 expect(headers.get('X-CSRF-TOKEN')).toBe('test-csrf');
 const value=headers.get('Idempotency-Key');
 expect(value).toBeTruthy();
 return value;
}

afterEach(()=>vi.unstubAllGlobals());

describe('写请求幂等键',()=>{
 it('响应丢失后使用原标识重试，成功后再次操作生成新标识',async()=>{
  const fetch=vi.fn()
   .mockRejectedValueOnce(new TypeError('network connection closed'))
   .mockResolvedValueOnce(response(200,{success:true}))
   .mockResolvedValueOnce(response(200,{success:true}));
  vi.stubGlobal('fetch',fetch);
  setCsrf('test-csrf');
  const pay=()=>api('/orders/retry-test/payment','POST',{});
  await expect(pay()).rejects.toMatchObject({code:'NETWORK'});
  await expect(pay()).resolves.toEqual({success:true});
  expect(key(fetch,1)).toBe(key(fetch,0));
  await expect(pay()).resolves.toEqual({success:true});
  expect(key(fetch,2)).not.toBe(key(fetch,1));
 });

 it('收到业务 409 后重新提交使用新标识',async()=>{
  const fetch=vi.fn()
   .mockResolvedValueOnce(response(409,{code:'VERSION_CONFLICT',message:'商品版本已变化'}))
   .mockResolvedValueOnce(response(200,{id:'test-product'}));
  vi.stubGlobal('fetch',fetch);
  setCsrf('test-csrf');
  const update=()=>api('/products/conflict-test','PATCH',{saleStatus:'OFF_SHELF',version:3});
  await expect(update()).rejects.toMatchObject({code:'VERSION_CONFLICT',status:409});
  await expect(update()).resolves.toEqual({id:'test-product'});
  expect(key(fetch,1)).not.toBe(key(fetch,0));
 });
});

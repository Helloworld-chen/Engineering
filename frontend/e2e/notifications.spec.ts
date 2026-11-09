import {test,expect,type Page,type APIRequestContext} from '@playwright/test';

test.beforeEach(()=>test.skip(process.env.NOTIFICATION_E2E_ISOLATED!=='1','仅在独立数据库执行，避免标记用户现有通知'));
async function login(page:Page,username:string){
 await page.goto('/login');await page.getByLabel('用户名',{exact:true}).fill(username);
 await page.getByLabel('密码',{exact:true}).fill('Campus123!');
 await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page).toHaveURL(/\/$/);
}
async function post(client:APIRequestContext,path:string,data:unknown){
 const {csrfToken}=await (await client.get('/api/v1/session')).json();
 const response=await client.post('/api/v1'+path,{headers:{'X-CSRF-TOKEN':csrfToken,'Idempotency-Key':crypto.randomUUID()},data});
 expect(response.ok(),await response.text()).toBeTruthy();return response.json();
}
async function clear(client:APIRequestContext){
 const {latestId}=await (await client.get('/api/v1/notifications')).json();
 if(latestId)await post(client,'/notifications/read-all',{throughId:latestId});
}
async function product(client:APIRequestContext,admin:APIRequestContext){
 const {csrfToken}=await (await client.get('/api/v1/session')).json();
 const file=await client.post('/api/v1/files',{headers:{'X-CSRF-TOKEN':csrfToken},multipart:{purpose:'PRODUCT',file:{name:'book.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64')}}});
 expect(file.ok()).toBeTruthy();
 const p=await post(client,'/products',{title:'消息提醒 · 二手课本 '+crypto.randomUUID(),description:'独立通知回归',category:'图书',price:100,images:[(await file.json()).id]});
 await post(admin,'/moderation/products/'+p.id,{decision:'APPROVED',reason:'资料完整',version:p.version});return p;
}
test('买卖消息即时提醒、手机入口与跨标签页已读同步',async({browser,baseURL},info)=>{
 const contexts=await Promise.all(['seller','buyer','admin'].map(async user=>{
  const context=await browser.newContext({baseURL});const page=await context.newPage();await login(page,user);
  return {context,page,client:context.request};
 }));
 const [seller,buyer,admin]=contexts;
 try{
  const p=await product(seller.client,admin.client);
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('商品审核结果');
  await clear(seller.client);await clear(buyer.client);
  await seller.page.reload();
  await expect(seller.page).toHaveTitle('校园集市');
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0);
  const second=await seller.context.newPage();await second.goto('/');
  await expect(second.getByRole('link',{name:'通知',exact:true})).toHaveAccessibleDescription('0条未读通知');
  const order=await post(buyer.client,'/orders',{productId:p.id});
  for(const page of [seller.page,second]){
   await expect(page.getByRole('complementary',{name:'新消息提醒'})).toContainText('有买家下单，等待付款');
   await expect(page.getByRole('link',{name:'通知',exact:true})).toHaveAccessibleDescription('1条未读通知');
   await expect(page).toHaveTitle('（1条未读）校园集市');
  }
  await expect(buyer.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('订单已创建');
  await post(buyer.client,'/orders/'+order.id+'/payment',{});
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('买家已付款，请及时发货');
  await expect(seller.page).toHaveTitle('（2条未读）校园集市');
  for(const width of [1440,390]){
   await seller.page.setViewportSize({width,height:900});
   await expect(seller.page.getByRole('link',{name:'通知',exact:true})).toBeVisible();
   expect(await seller.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
   await seller.page.screenshot({path:info.outputPath('notifications-'+width+'.png'),fullPage:true});
  }
  await seller.page.getByRole('link',{name:'查看通知',exact:true}).click();
  await expect(seller.page).toHaveTitle('（2条未读）校园集市');
  await seller.page.getByRole('article').filter({hasText:'买家已付款，请及时发货'}).getByRole('button',{name:'标为已读',exact:true}).click();
  await expect(second).toHaveTitle('（1条未读）校园集市');
  // Dismiss the existing toast; the next read event must not produce another one.
  const close=second.getByRole('button',{name:'关闭消息提醒'});if(await close.count())await close.click();
  await seller.page.getByRole('button',{name:'全部标为已读',exact:true}).click();
  await expect(seller.page).toHaveTitle('校园集市');await expect(second).toHaveTitle('校园集市');
  await expect(second.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0);
  await expect(seller.page.getByRole('link',{name:'通知',exact:true})).toHaveAccessibleDescription('0条未读通知');
  await expect(buyer.page).toHaveTitle('（2条未读）校园集市');

  await seller.context.setOffline(true);
  const refund=await post(buyer.client,'/cases',{orderId:order.id,kind:'REFUND',reason:'申请退款'});
  await seller.context.setOffline(false);
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toBeVisible({timeout:15000});
  await expect(seller.page).toHaveTitle('（1条未读）校园集市');
  await seller.page.reload();
  await expect(seller.page).toHaveTitle('（1条未读）校园集市');
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0);
  await post(seller.client,'/cases/'+refund.id+'/actions',{action:'REFUND',reason:'通知回归完成，同意退款'});
 }finally{await Promise.all(contexts.map(({context})=>context.close()))}
});

test('通知同步失败保留未知状态，恢复后计数及全部已读可用',async({page})=>{
 let failure=true;
 await page.route('**/api/v1/notifications',async route=>{
  if(failure)await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'UNAVAILABLE',message:'临时不可用'})});
  else await route.continue();
 });
 await login(page,'seller');
 await expect(page.getByText('通知同步失败：临时不可用')).toBeVisible();
 await expect(page.getByRole('link',{name:'通知',exact:true})).toHaveAccessibleDescription('未读数量暂不可用');
 failure=false;await page.getByRole('button',{name:'重试通知同步'}).click();
 const data=await (await page.request.get('/api/v1/notifications')).json();
 await expect(page.getByRole('link',{name:'通知',exact:true})).toHaveAccessibleDescription(data.unreadCount+'条未读通知');
 await expect(page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0);
});

for(const role of ['admin','support'])test(`${role}首次加载99条以上未读不逐条弹历史提醒`,async({page})=>{
 await page.route('**/api/v1/notifications',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({items:[{id:'9007199254740993',recipientId:role,aggregateId:'sample',message:'历史通知',isRead:false,createdAt:'2026-11-09T00:00:00.000Z'}],unreadCount:105,latestId:'9007199254740993'})}));
 await login(page,role);await expect(page.locator('.notification-badge')).toHaveText('99+');
 await expect(page).toHaveTitle('（105条未读）校园集市');
 await expect(page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0);
 await page.getByRole('button',{name:'退出',exact:true}).click();
 await expect(page).toHaveTitle('校园集市');await expect(page.getByRole('link',{name:'通知',exact:true})).toHaveCount(0);
});

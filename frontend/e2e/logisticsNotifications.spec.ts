import {test,expect,type Page,type APIRequestContext} from '@playwright/test';
test.beforeEach(()=>test.skip(process.env.NOTIFICATION_E2E_ISOLATED!=='1','仅使用独立数据库验证物流通知'));
async function login(page:Page,user:string){await page.goto('/login');await page.getByLabel('用户名',{exact:true}).fill(user);await page.getByLabel('密码',{exact:true}).fill('Campus123!');await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page).toHaveURL(new RegExp('/'+(user==='admin'?'admin':user==='support'?'support':'')+'$'))}
async function post(client:APIRequestContext,path:string,data:unknown){const {csrfToken}=await(await client.get('/api/v1/session')).json();const r=await client.post('/api/v1'+path,{headers:{'X-CSRF-TOKEN':csrfToken,'Idempotency-Key':crypto.randomUUID()},data});expect(r.ok(),await r.text()).toBeTruthy();return r.json()}
async function photo(client:APIRequestContext,purpose:string){const {csrfToken}=await(await client.get('/api/v1/session')).json();const r=await client.post('/api/v1/files',{headers:{'X-CSRF-TOKEN':csrfToken},multipart:{purpose,file:{name:'evidence.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jM1sAAAAASUVORK5CYII=','base64')}}});expect(r.ok()).toBeTruthy();return(await r.json()).id}
async function clear(page:Page){const {latestId}=await(await page.request.get('/api/v1/notifications')).json();if(latestId)await post(page.request,'/notifications/read-all',{throughId:latestId});await page.reload();await expect(page).toHaveTitle('校园集市');await expect(page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0)}
async function snapshot(client:APIRequestContext){return(await client.get('/api/v1/notifications')).json()}
async function received(client:APIRequestContext,id:string,message:string){await expect.poll(async()=>(await snapshot(client)).items.some((n:any)=>n.aggregateId===id&&n.message===message)).toBeTruthy()}
async function ship(client:APIRequestContext,id:string,returning:boolean){return post(client,`/orders/${id}/${returning?'return-shipment':'shipment'}`,{trackingNumber:'LOGISTICS-'+crypto.randomUUID(),goods:[await photo(client,returning?'RETURN_GOODS':'GOODS')],packaging:[await photo(client,returning?'RETURN_PACKAGING':'PACKAGING')]})}

test('正向物流只提醒买家，退货物流只提醒卖家，双方页面仍自动更新',async({browser,baseURL},info)=>{
 test.setTimeout(90000);
 const actors=await Promise.all(['seller','buyer','admin','support'].map(async user=>{const context=await browser.newContext({baseURL});const page=await context.newPage();await login(page,user);return {context,page,client:context.request}}));
 const [seller,buyer,admin,support]=actors;
 try{
  const product=await post(seller.client,'/products',{title:'物流收件人回归 '+crypto.randomUUID(),description:'独立测试',category:'图书',price:100,images:[await photo(seller.client,'PRODUCT')]});
  await post(admin.client,'/moderation/products/'+product.id,{decision:'APPROVED',reason:'资料完整',version:product.version});
  const order=await post(buyer.client,'/orders',{productId:product.id});await post(buyer.client,`/orders/${order.id}/payment`,{});
  await received(seller.client,order.id,'买家已付款，请及时发货');await received(buyer.client,order.id,'付款成功，货款由平台暂管');
  for(const actor of [seller,buyer]){await actor.page.goto('/orders/'+order.id);await clear(actor.page)}
  const second=await seller.context.newPage();await second.goto('/orders/'+order.id);
  const sellerBefore=await snapshot(seller.client);await ship(seller.client,order.id,false);
  await expect(buyer.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('卖家已发货');
  await expect(buyer.page).toHaveTitle('（1条未读）校园集市');
  for(const page of [seller.page,second]){await expect(page.locator('main').getByText('运输中',{exact:true}).first()).toBeVisible();await expect(page).toHaveTitle('校园集市');await expect(page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0)}
  await expect(buyer.page.getByRole('button',{name:'确认收货',exact:true})).toBeVisible({timeout:16000});
  await expect(buyer.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('48小时内可确认收货');
  await expect(buyer.page).toHaveTitle('（2条未读）校园集市');
  for(const page of [seller.page,second]){await expect(page.locator('main').getByText('已送达',{exact:true}).first()).toBeVisible();await expect(page).toHaveTitle('校园集市')}
  expect((await snapshot(seller.client)).unreadCount).toBe(sellerBefore.unreadCount);
  expect((await snapshot(seller.client)).items.filter((n:any)=>n.aggregateId===order.id&&/卖家已发货|48小时内可确认收货/.test(n.message))).toHaveLength(0);
  const c=await post(buyer.client,'/cases',{orderId:order.id,kind:'COMPLAINT',reason:'商品损坏，申请退货'});await post(support.client,`/cases/${c.id}/actions`,{action:'APPROVE_RETURN',reason:'同意退货'});
  await received(seller.client,order.id,'售后处理进度已更新');await received(buyer.client,order.id,'售后处理进度已更新');
  await seller.page.goto('/cases/'+c.id);await clear(seller.page);await clear(buyer.page);
  const buyerBefore=await snapshot(buyer.client);await ship(buyer.client,order.id,true);
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('买家已寄回商品');await expect(seller.page).toHaveTitle('（1条未读）校园集市');
  await expect(buyer.page.getByText('退货预计送达')).toBeVisible();await expect(buyer.page).toHaveTitle('校园集市');await expect(buyer.page.getByRole('complementary',{name:'新消息提醒'})).toHaveCount(0);
  await expect(seller.page.getByRole('button',{name:'通过退货验收',exact:true})).toBeVisible({timeout:16000});
  await expect(seller.page.getByRole('complementary',{name:'新消息提醒'})).toContainText('卖家有24小时验收');await expect(seller.page).toHaveTitle('（2条未读）校园集市');
  await expect(buyer.page.getByText('退货验收剩余时间')).toBeVisible();await expect(buyer.page).toHaveTitle('校园集市');
  expect((await snapshot(buyer.client)).unreadCount).toBe(buyerBefore.unreadCount);
  expect((await snapshot(buyer.client)).items.filter((n:any)=>n.aggregateId===order.id&&/买家已寄回|卖家有24小时验收/.test(n.message))).toHaveLength(0);
  for(const width of [1440,390]){await seller.page.setViewportSize({width,height:900});await expect(seller.page.getByRole('link',{name:'通知',exact:true})).toHaveAccessibleDescription('2条未读通知');expect(await seller.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();await seller.page.screenshot({path:info.outputPath('logistics-'+width+'.png'),fullPage:true})}
 }finally{await Promise.all(actors.map(a=>a.context.close()))}
});
